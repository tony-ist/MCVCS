package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.block.BlockTypes;

/**
 * {@code /vcs unplace [-k]}: takes the selected placement out of its build and empties its box, leaving the ground
 * clear; {@link #KEEP} leaves the blocks standing instead and only stops the placement being tracked.
 * <p>
 * The box holds a version of the build, which is on disk either way, so removing the placement loses nothing and is
 * done at once. What is not on disk is work done since: a box that differs from the version it holds is asked about
 * first, and {@code /vcs confirmUnplace} then goes through with it. That is the only thing the confirmation is
 * there for, so it is asked whether the blocks are about to be emptied or kept.
 * <p>
 * The build itself, and every version of it, stays on disk: only this copy of it in the world stops being tracked.
 * A build may end up with no placements at all, and {@code /vcs place} puts it back into the world.
 */
public final class VcsCommandUnplace {
	/** Flag after {@code /vcs unplace} that leaves the blocks standing instead of emptying the placement's box. */
	public static final String KEEP = "-k";

	/** What each player's last {@code /vcs unplace} asked about, and whether to keep its blocks, by player UUID. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private record Pending(String build, String placement, boolean keep) {
	}

	static final VcsHelp HELP = new VcsHelp("unplace", "/vcs unplace [" + KEEP + "]",
		"stop tracking the selected placement and empty its box",
		"Removes your selected placement from its build and empties its box, since the version it holds is kept on disk. Add " + KEEP + " to leave its blocks standing as ordinary world blocks instead. If the box has uncommitted changes, they would be lost, so you are asked to confirm with /vcs confirmUnplace first. The build and all its versions stay: run /vcs place to put it back into the world.");
	static final VcsHelp CONFIRM_HELP = new VcsHelp("confirmUnplace", "/vcs confirmUnplace",
		"remove the placement your last /vcs unplace named",
		"Removes the placement your last /vcs unplace named, emptying its box unless " + KEEP + " was given, and discards the uncommitted changes it was holding. The build's versions are untouched; run /vcs place to put it back into the world.");

	private VcsCommandUnplace() {
	}

	/** Forgets a player's pending confirmation when they leave. */
	static void register() {
		// A confirmation left behind by a player who logged out must not remove anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
	}

	/**
	 * Removes the placement straight away, or asks first when its box holds work no version does.
	 *
	 * @param keep whether to leave the blocks standing instead of emptying the placement's box
	 */
	static int run(CommandSourceStack source, boolean keep) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		// Only what was built since the box last held a version would be lost, so only that is worth asking about.
		int uncommitted = uncommitted(placement, level, player);
		if (uncommitted == 0) {
			return remove(source, player, placement, keep);
		}

		PENDING.put(player.getUUID(), new Pending(placement.build().name(), placement.name(), keep));
		source.sendSuccess(() -> Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is modified: " + uncommitted
			+ (uncommitted == 1 ? " block differs" : " blocks differ") + " from v" + placement.head() + ", and removing it would lose "
			+ (uncommitted == 1 ? "that change" : "those changes") + (keep ? ", though its blocks would be left standing" : " along with its blocks")
			+ ". Run ").append(ChatButtons.command("/vcs commit")).append(" to save them first, ").append(ChatButtons.command("/vcs diff"))
			.append(" to see them, or ").append(ChatButtons.command("/vcs confirmUnplace")).append(" to remove it anyway."), false);
		return 1;
	}

	/** {@code /vcs confirmUnplace}. */
	static int confirm(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		Pending pending = PENDING.remove(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.literal("Nothing to confirm; run ").append(ChatButtons.command("/vcs unplace")).append(" first"));
			return 0;
		}

		// The build may have gone, or the placement been removed by someone else, since they asked.
		Optional<BuildPlacement> found = BuildRegistry.find(server, pending.build()).flatMap(build -> BuildPlacement.of(build, pending.placement()));
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No placement ").append(VcsMessages.name(pending.build() + Build.LABEL_SEPARATOR + pending.placement())).append(" in this world"));
			return 0;
		}
		return remove(source, player, found.get(), pending.keep());
	}

	/**
	 * Takes {@code placement} out of its build, emptying its box unless {@code keep}. The last thing both an
	 * unmodified {@code /vcs unplace} and {@code /vcs confirmUnplace} do.
	 */
	private static int remove(CommandSourceStack source, ServerPlayer player, BuildPlacement placement, boolean keep) {
		MinecraftServer server = source.getServer();
		BuildBox box = placement.box();
		if (!keep) {
			ServerLevel level = server.getLevel(placement.dimension());
			if (level == null) {
				source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here"));
				return 0;
			}
			if (!clear(player, level, box)) {
				source.sendFailure(Component.literal("Failed to empty the box of ").append(VcsMessages.placement(placement)).append("; nothing was removed"));
				return 0;
			}
		}

		try {
			BuildStorage.update(placement.build().withoutPlacement(placement.name()));
			BuildStorage.clearSelectionsOf(placement.build().name(), placement.name());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to unplace '{}' for {}", placement.label(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to remove placement: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} unplaced '{}' at {}, {}", player.getGameProfile().name(), placement.label(), box.min().toShortString(),
			keep ? "leaving its blocks" : "emptying its box");

		// Anyone previewing or diffing the placement is looking at a box that is no longer tracked.
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (BuildRegistry.selected(other).filter(selected -> selected.label().equals(placement.label())).isEmpty()) {
				continue;
			}
			if (PreviewSender.canSend(other)) {
				PreviewSender.clear(other);
			}
			if (DiffSender.canSend(other)) {
				DiffSender.clear(other);
			}
		}
		// The placement is gone for every client, and for whoever had it selected.
		BuildSync.broadcast(server);

		source.sendSuccess(() -> Component.literal("Removed placement ").append(VcsMessages.placement(placement))
			.append(keep ? "; its blocks were left standing" : " and emptied its box")
			.append("; build ").append(VcsMessages.name(placement.build().name())).append(" keeps its " + placement.build().version()
				+ (placement.build().version() == 1 ? " version" : " versions")), false);
		return 1;
	}

	/**
	 * How many blocks inside {@code placement}'s box differ from the version it holds, i.e. how much work removing it
	 * would lose. A version whose schematic cannot be read counts as differing everywhere, so the player is asked
	 * rather than having the box emptied on the strength of a comparison that could not be made.
	 */
	private static int uncommitted(BuildPlacement placement, ServerLevel level, ServerPlayer player) {
		BuildBox box = placement.box();
		try {
			BuildDiff changes = BuildDiff.between(
				BoxSnapshot.ofClipboard(box, BuildStorage.readSchematic(placement.build().name(), placement.head()), box),
				BoxSnapshot.ofLevel(box, level));
			return changes.size();
		} catch (NoSuchFileException e) {
			MCVCS.LOGGER.warn("No schematic for '{}' v{} at {}, so {} is asked to confirm unplacing it",
				placement.label(), placement.head(), e.getFile(), player.getGameProfile().name());
			return Math.toIntExact(box.volume());
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to compare '{}' with v{} for {}", placement.label(), placement.head(), player.getGameProfile().name(), e);
			return Math.toIntExact(box.volume());
		}
	}

	/**
	 * Empties {@code box}, the way a checkout empties one: without block updates and outside the player's WorldEdit
	 * history, so {@code //undo} never brings the blocks back.
	 */
	private static boolean clear(ServerPlayer player, ServerLevel level, BuildBox box) {
		FabricAdapter adapter = FabricAdapter.get();
		Player actor = adapter.fromNativePlayer(player);
		try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder().world(adapter.fromNativeWorld(level)).actor(actor).maxBlocks(-1).build()) {
			editSession.setSideEffectApplier(BuildPlacer.sideEffects());
			editSession.setBlocks(box.region(level), Objects.requireNonNull(BlockTypes.AIR).getDefaultState());
			return true;
		} catch (WorldEditException e) {
			MCVCS.LOGGER.error("Failed to empty {} for {}", box, player.getGameProfile().name(), e);
			return false;
		}
	}
}
