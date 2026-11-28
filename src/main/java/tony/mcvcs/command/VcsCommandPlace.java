package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.Placement;
import tony.mcvcs.build.Schematic;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.PlacePreviewMovePayload;
import tony.mcvcs.network.PreviewSender;

/**
 * {@code /vcs place <buildname> [version | tag | latest] [placementname] [-f]}: shows another copy of a build where the
 * player stands, as a preview only; {@code /vcs confirmPlace [-f]} then puts it into the world as a placement of its
 * own, and {@code /vcs cancelPlace} drops it.
 * <p>
 * The copy starts with its top north-west corner one block below the player's feet, so the build hangs below them,
 * extending east and south. From there the player slides it about
 * with the numpad keys and the mouse wheel, which the client's {@code PlacePreviewKeys} handles, and the client tells the server where it
 * stands with a {@link PlacePreviewMovePayload}. Nothing in the world is touched until the placement is confirmed,
 * and where it is confirmed fixes the placement's {@link Placement#origin} for good; checking out another version
 * there afterwards grows or shrinks its box around the build rather than moving it.
 * <p>
 * A client without the mod cannot draw the preview, so for those players {@code /vcs place} still places the copy
 * at once, where they stand, the way it always did.
 * <p>
 * The new placement lives its own life from then on: it holds the version it was placed at, can be modified and
 * checked out on its own, and a commit from it saves the build's next version like a commit from any other.
 * Placements may never overlap, and blocks already standing in the way are refused unless {@link VcsCommand#FORCE}
 * is given, which overwrites them for good.
 */
public final class VcsCommandPlace {
	/** What each player's last {@code /vcs place} is waiting to put down, by player UUID. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	/**
	 * A copy being aligned: everything {@code /vcs confirmPlace} needs to put it down. Only {@link #box} ever
	 * changes, as the player moves the preview, and it always keeps the version's own size.
	 */
	private record Pending(String build, int version, String placement, boolean force, ResourceKey<Level> dimension, BuildBox box) {
		Pending movedTo(BlockPos min) {
			return new Pending(build, version, placement, force, dimension, new BuildBox(min, min.offset(box.sizeX() - 1, box.sizeY() - 1, box.sizeZ() - 1)));
		}

		String label() {
			return build + Build.LABEL_SEPARATOR + placement;
		}
	}

	static final VcsHelp HELP = new VcsHelp("place", "/vcs place <buildname> [version | label | latest] [copyname] [" + VcsCommand.FORCE + "]",
		"put another copy of a build in the world",
		"Shows a copy of the build where you stand. Hold Left Alt and scroll to move it, or use Numpad 8, 2, 4, 6, 7 and 9. Press Numpad 5 or run `/vcs confirmPlace` to place it, or `/vcs cancelPlace` to drop it. Each copy can be changed, saved and loaded on its own.",
		new VcsHelp.Key(VcsCommand.FORCE, "overwrite the blocks where the copy goes"));
	static final VcsHelp CONFIRM_HELP = new VcsHelp("confirmPlace", "/vcs confirmPlace [" + VcsCommand.FORCE + "]",
		"place the copy from `/vcs place`",
		"Places the copy from `/vcs place` where you moved it, and selects it. Refuses if it is too high or too low, overlaps another copy, or blocks are in the way.",
		new VcsHelp.Key(VcsCommand.FORCE, "overwrite the blocks in the way"));
	static final VcsHelp CANCEL_HELP = new VcsHelp("cancelPlace", "/vcs cancelPlace",
		"drop the copy from `/vcs place`",
		"Removes the copy from `/vcs place` without placing anything.");

	private VcsCommandPlace() {
	}

	/** Forgets a player's pending copy when they leave, and listens for the moves their client makes. */
	static void register() {
		// A copy left behind by a player who logged out must not be placed when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
		ServerPlayNetworking.registerGlobalReceiver(PlacePreviewMovePayload.TYPE, (payload, context) -> move(context.player(), payload.min()));
	}

	/**
	 * @param version       the version to place, by number or tag, or {@link VersionRef#DEFAULT} for the build's latest one
	 * @param placementName the name for the new placement, or null for the build's next free one
	 * @param force         whether to overwrite blocks standing where the copy goes instead of refusing
	 */
	static int run(CommandSourceStack source, String buildName, VersionRef version, String placementName, boolean force) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> found = BuildRegistry.find(source.getServer(), buildName);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append("; see ").append(ChatButtons.command("/vcs builds")));
			return 0;
		}

		Build build = found.get();
		Optional<Integer> resolved = version.resolve(source, build, build.version());
		if (resolved.isEmpty()) {
			return 0;
		}
		int placed = resolved.get();
		String name = placementName == null ? build.freePlacementName() : placementName;
		if (!VcsCommandCreate.isPlacementNameValid(source, name)) {
			return 0;
		}
		if (build.placement(name).isPresent()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" already has a copy called ").append(VcsMessages.name(name)));
			return 0;
		}

		ServerLevel level = player.level();
		BuildBox box = atFeet(build.extent(placed), player.blockPosition());
		Schematic schematic = read(source, buildName, placed, player);
		if (schematic == null) {
			return 0;
		}

		// Without the mod on the client there is nothing to align the copy with, so it goes down where they stand.
		if (!PreviewSender.canSendPlace(player)) {
			return place(source, player, level, build, name, placed, box, force, schematic);
		}

		// A second /vcs place replaces whatever the first one was still showing, preview and all.
		PENDING.put(player.getUUID(), new Pending(buildName, placed, name, force, level.dimension(), box));
		PreviewSender.sendPlace(player, buildName + Build.LABEL_SEPARATOR + name, placed, level.dimension(), box, schematic, force);
		MCVCS.LOGGER.info("{} is placing build '{}' v{} as placement '{}', previewed at {} in {}",
			player.getGameProfile().name(), buildName, placed, name, box.min().toShortString(), level.dimension().identifier());

		source.sendSuccess(() -> Component.literal("Showing ").append(VcsMessages.name(buildName + Build.LABEL_SEPARATOR + name))
			.append(" " + build.versionLabel(placed) + " (" + VcsMessages.size(box) + ", " + box.volume() + " blocks)")
			.append("; hold Left Alt and scroll to move it, then press Numpad 5 or run ").append(ChatButtons.command("/vcs confirmPlace"))
			.append(" to place it, or ").append(ChatButtons.command("/vcs cancelPlace")).append(" to drop it"), false);
		return 1;
	}

	/** {@code /vcs confirmPlace [-f]}: puts the copy down where the player has moved it. */
	static int confirm(CommandSourceStack source, boolean force) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.literal("Nothing to place; run ").append(ChatButtons.template("/vcs place <buildname>")).append(" first"));
			return 0;
		}

		MinecraftServer server = source.getServer();
		// The build may have gone, or the name been taken, since the copy was first shown.
		Optional<Build> found = BuildRegistry.find(server, pending.build());
		if (found.isEmpty()) {
			drop(player);
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(pending.build())).append(" any more"));
			return 0;
		}
		Build build = found.get();
		if (build.placement(pending.placement()).isPresent()) {
			drop(player);
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(pending.build())).append(" already has a copy called ").append(VcsMessages.name(pending.placement())));
			return 0;
		}
		if (pending.version() > build.version()) {
			drop(player);
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(pending.build())).append(" only has versions 1 to " + build.version()));
			return 0;
		}
		ServerLevel level = server.getLevel(pending.dimension());
		if (level == null) {
			drop(player);
			source.sendFailure(Component.literal("The copy is in " + pending.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		Schematic schematic = read(source, pending.build(), pending.version(), player);
		if (schematic == null) {
			return 0;
		}
		int result = place(source, player, level, build, pending.placement(), pending.version(), pending.box(), force || pending.force(), schematic);
		// A refused placement keeps the copy up, so it can be moved somewhere clear and confirmed again.
		if (result == 1) {
			drop(player);
		}
		return result;
	}

	/** {@code /vcs cancelPlace}. */
	static int cancel(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.literal("Nothing to cancel; run ").append(ChatButtons.template("/vcs place <buildname>")).append(" first"));
			return 0;
		}

		drop(player);
		source.sendSuccess(() -> Component.literal("Dropped the copy of ").append(VcsMessages.name(pending.label())).append("; nothing was placed"), false);
		return 1;
	}

	/**
	 * The player's client moved the copy to {@code min}. Only the position is taken: the box keeps the size the
	 * version has, so a client cannot ask for a box of its own making.
	 */
	private static void move(ServerPlayer player, BlockPos min) {
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null) {
			// The copy was confirmed or cancelled while the move was in flight; the client drops it when it hears.
			return;
		}
		PENDING.put(player.getUUID(), pending.movedTo(min));
	}

	/** Forgets the player's copy and stops their client drawing it. */
	private static void drop(ServerPlayer player) {
		PENDING.remove(player.getUUID());
		if (PreviewSender.canSendPlace(player)) {
			PreviewSender.clearPlace(player);
		}
	}

	/** Reads a version's schematic, reporting to {@code source} and returning null if it cannot be had. */
	private static Schematic read(CommandSourceStack source, String buildName, int version, ServerPlayer player) {
		try {
			return BuildStorage.readSchematic(buildName, version);
		} catch (NoSuchFileException e) {
			source.sendFailure(VcsMessages.missingVersion(buildName, version));
			return null;
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read build '{}' v{} for {}", buildName, version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load v" + version + ": " + e.getMessage()));
			return null;
		}
	}

	/**
	 * Puts {@code schematic}, version {@code placed} of {@code build}, into {@code box} as a new placement called
	 * {@code name}, and selects it. The last thing both the preview-less path and {@code /vcs confirmPlace} do.
	 */
	private static int place(CommandSourceStack source, ServerPlayer player, ServerLevel level, Build build, String name, int placed, BuildBox box, boolean force, Schematic schematic) {
		String buildName = build.name();
		BuildBox extent = build.extent(placed);
		BuildPlacement placement = new BuildPlacement(build, name, new Placement(level.dimension(), box.min().subtract(extent.min()), placed));

		// Blocks outside the build height cannot be set, so the copy would be cut off.
		if (box.max().getY() > level.getMaxY()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" is too high here; move it down"));
			return 0;
		}
		if (box.min().getY() < level.getMinY()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" is too low here; move it up"));
			return 0;
		}
		// Every block belongs to at most one placement, whatever is standing there.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), level.dimension(), box, null);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Placing build ").append(VcsMessages.name(buildName)).append(" here would overlap ")
				.append(VcsMessages.placement(overlapping.get())).append(VcsMessages.NO_OVERLAP));
			return 0;
		}
		// Blocks that are in the way are not part of any build, so they are only overwritten when asked for.
		int inTheWay = nonAir(box, level);
		if (inTheWay > 0 && !force) {
			source.sendFailure(Component.literal("Placing build ").append(VcsMessages.name(buildName)).append(" here would overwrite " + inTheWay
				+ (inTheWay == 1 ? " block" : " blocks") + " in the way; move somewhere clear or add " + VcsCommand.FORCE + " to overwrite them"));
			return 0;
		}

		try {
			BuildPlacer.place(level, List.of(box), schematic, box);
		} catch (IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to place build '{}' v{} for {}", buildName, placed, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to place: " + e.getMessage()));
			return 0;
		}

		Build updated = placement.applied();
		try {
			BuildStorage.update(updated);
			// The placement is new to every client, not just to the one that made it.
			BuildSync.broadcast(source.getServer());
			BuildRegistry.select(player, updated, name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to record placement '{}' of build '{}' for {}", name, buildName, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Placed build ").append(VcsMessages.name(buildName)).append(" but failed to remember the copy: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} placed build '{}' v{} as placement '{}' at {} in {}, overwriting {} blocks",
			player.getGameProfile().name(), buildName, placed, name, box.min().toShortString(), level.dimension().identifier(), inTheWay);

		source.sendSuccess(() -> {
			MutableComponent message = Component.literal("Placed ").append(VcsMessages.name(buildName + Build.LABEL_SEPARATOR + name))
				.append(" " + build.versionLabel(placed) + " (" + VcsMessages.size(box) + ", " + box.volume() + " blocks) at " + box.min().toShortString());
			if (inTheWay > 0) {
				// Only a forced placement gets here; those blocks are gone for good.
				message.append(", overwriting " + inTheWay + (inTheWay == 1 ? " block" : " blocks") + " that stood there");
			}
			return message;
		}, false);
		return 1;
	}

	/**
	 * Where a version of extent {@code extent} lands for a player standing at {@code feet}: with its top north-west
	 * corner one block below their feet, so the build hangs below them and extends east and south.
	 */
	static BuildBox atFeet(BuildBox extent, BlockPos feet) {
		BlockPos min = new BlockPos(feet.getX(), feet.getY() - extent.sizeY(), feet.getZ());
		return new BuildBox(min, min.offset(extent.sizeX() - 1, extent.sizeY() - 1, extent.sizeZ() - 1));
	}

	/** How many blocks inside {@code box} are not air, i.e. how much placing there would overwrite. */
	private static int nonAir(BuildBox box, ServerLevel level) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
			if (!level.getBlockState(pos).isAir()) {
				count++;
			}
		}
		return count;
	}
}
