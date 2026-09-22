package tony.mcvcs.command;

import java.io.IOException;
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
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
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
 * {@code /vcs delete <buildname> [-c]}: asks the player to confirm deleting the build; nothing is touched yet.
 * {@code /vcs confirmDelete} then removes the build's folder with every version in it and every player's selection of
 * any of its placements. The blocks of those placements are left standing in the world unless {@link #CLEAR} was
 * given, in which case every one of their boxes is emptied first. The confirmation is remembered until it is used,
 * replaced by another {@code /vcs delete}, or the player leaves.
 */
public final class VcsCommandDelete {
	/** Flag after {@code /vcs delete} that also empties the box of every placement of the build. */
	public static final String CLEAR = "-c";

	/**
	 * The build each player's last {@code /vcs delete} asked to delete, and whether to clear its placements, by
	 * player UUID. Only the server thread touches this; a player's entry goes when they leave.
	 */
	private static final Map<UUID, Pending> PENDING_DELETES = new HashMap<>();

	private record Pending(Build build, boolean clear) {
	}

	static final VcsHelp HELP = new VcsHelp("delete", "/vcs delete <buildname> [" + CLEAR + "]",
		"delete a build, once you confirm",
		"Asks you to confirm deleting the build. Nothing is deleted until you run /vcs confirmDelete; the request is forgotten if you leave the server first, and a second /vcs delete replaces it. The blocks of its placements are left standing in the world unless you add " + CLEAR + ", which empties every one of their boxes as well.");
	static final VcsHelp CONFIRM_HELP = new VcsHelp("confirmDelete", "/vcs confirmDelete",
		"delete the build your last /vcs delete named",
		"Deletes the build your last /vcs delete named: its folder with every version in it is removed and anyone who had one of its placements selected loses that selection. This cannot be undone.");

	private VcsCommandDelete() {
	}

	/** Forgets a player's pending confirmation when they leave. */
	static void register() {
		// A confirmation left behind by a player who logged out must not delete anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING_DELETES.remove(handler.player.getUUID()));
	}

	/** Only asks for confirmation; {@link #confirm} does the deleting. */
	static int run(CommandSourceStack source, String buildName, boolean clear) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world"));
			return 0;
		}

		int placements = build.get().placements().size();
		PENDING_DELETES.put(player.getUUID(), new Pending(build.get(), clear));
		source.sendSuccess(() -> Component.literal("Delete build ").append(VcsMessages.name(buildName))
			.append(clear ? ", emptying its " + placements + (placements == 1 ? " placement" : " placements") : ", leaving its placements standing as blocks")
			.append("? This cannot be undone, all versions will be lost! Run ").append(ChatButtons.command("/vcs confirmDelete")).append(" to proceed."), false);
		return 1;
	}

	/** {@code /vcs confirmDelete}. */
	static int confirm(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		Pending pending = PENDING_DELETES.remove(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.literal("Nothing to confirm; run ").append(ChatButtons.template("/vcs delete <buildname>")).append(" first"));
			return 0;
		}

		Build build = pending.build();
		// The build may have gone, or the player moved to another world, since they asked.
		Optional<Build> current = BuildRegistry.find(server, build.name());
		if (!build.isIn(server) || current.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(build.name())).append(" in this world"));
			return 0;
		}

		// Read again: placements may have been added or moved since the confirmation was asked for.
		build = current.get();
		if (pending.clear()) {
			for (BuildPlacement placement : BuildPlacement.allOf(build)) {
				ServerLevel level = server.getLevel(placement.dimension());
				if (level == null) {
					source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier()
						+ ", which does not exist here; nothing was deleted"));
					return 0;
				}
				if (!clear(player, level, placement)) {
					source.sendFailure(Component.literal("Failed to empty the box of ").append(VcsMessages.placement(placement)).append("; nothing was deleted"));
					return 0;
				}
			}
		}

		// Anyone previewing or diffing a placement of the build is looking at a version that is about to disappear.
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (BuildRegistry.selected(other).filter(selected -> selected.build().name().equals(pending.build().name())).isEmpty()) {
				continue;
			}
			if (PreviewSender.canSend(other)) {
				PreviewSender.clear(other);
			}
			if (DiffSender.canSend(other)) {
				DiffSender.clear(other);
			}
		}

		try {
			BuildStorage.delete(build.name());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to delete build '{}' for {}", build.name(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to delete build: " + e.getMessage()));
			// Whatever was removed before the failure is gone for everyone, so the clients must hear about it anyway.
			BuildSync.broadcast(server);
			return 0;
		}
		MCVCS.LOGGER.info("{} deleted build '{}' with {} versions and {} placements", player.getGameProfile().name(), build.name(), build.version(), build.placements().size());
		// The build and any selection of it are gone, so every client's list and possibly its selection changed.
		BuildSync.broadcast(server);

		Build deleted = build;
		source.sendSuccess(() -> Component.literal("Deleted build ").append(VcsMessages.name(deleted.name())).append(" and its " + deleted.version()
			+ (deleted.version() == 1 ? " version" : " versions")), false);
		return 1;
	}

	/**
	 * Empties a placement's box, the way a checkout empties one: without block updates and outside the player's
	 * WorldEdit history, so {@code //undo} never brings the blocks back.
	 */
	private static boolean clear(ServerPlayer player, ServerLevel level, BuildPlacement placement) {
		FabricAdapter adapter = FabricAdapter.get();
		Player actor = adapter.fromNativePlayer(player);
		try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder().world(adapter.fromNativeWorld(level)).actor(actor).maxBlocks(-1).build()) {
			editSession.setSideEffectApplier(BuildPlacer.sideEffects());
			editSession.setBlocks(placement.box().region(level), Objects.requireNonNull(BlockTypes.AIR).getDefaultState());
			return true;
		} catch (WorldEditException e) {
			MCVCS.LOGGER.error("Failed to empty the box of '{}' for {}", placement.label(), player.getGameProfile().name(), e);
			return false;
		}
	}
}
