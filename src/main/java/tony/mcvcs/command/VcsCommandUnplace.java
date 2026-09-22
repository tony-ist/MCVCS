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
import tony.mcvcs.build.BuildBox;
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
 * {@code /vcs unplace [-c]}: asks the player to confirm taking the selected placement out of the build; nothing is
 * touched yet. {@code /vcs confirmUnplace} then removes it. The blocks are left standing where they are unless
 * {@link #CLEAR} was given, in which case the placement's box is emptied as well.
 * <p>
 * The build itself, and every version of it, stays on disk: only this copy of it in the world stops being tracked.
 * A build may end up with no placements at all, and {@code /vcs place} puts it back into the world.
 */
public final class VcsCommandUnplace {
	/** Flag after {@code /vcs unplace} that also empties the placement's box instead of leaving the blocks standing. */
	public static final String CLEAR = "-c";

	/** What each player's last {@code /vcs unplace} asked to remove, and whether to clear its blocks, by player UUID. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private record Pending(String build, String placement, boolean clear) {
	}

	static final VcsHelp HELP = new VcsHelp("unplace", "/vcs unplace [" + CLEAR + "]",
		"stop tracking the selected placement, once you confirm",
		"Asks you to confirm removing your selected placement from its build. Nothing happens until you run /vcs confirmUnplace. The build and all its versions stay: only this copy of it in the world stops being tracked, and its blocks are left standing unless you add " + CLEAR + ", which empties its box as well.");
	static final VcsHelp CONFIRM_HELP = new VcsHelp("confirmUnplace", "/vcs confirmUnplace",
		"remove the placement your last /vcs unplace named",
		"Removes the placement your last /vcs unplace named from its build, emptying its box if " + CLEAR + " was given. The build's versions are untouched; run /vcs place to put it back into the world.");

	private VcsCommandUnplace() {
	}

	/** Forgets a player's pending confirmation when they leave. */
	static void register() {
		// A confirmation left behind by a player who logged out must not remove anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
	}

	/** Only asks for confirmation; {@link #confirm} does the removing. */
	static int run(CommandSourceStack source, boolean clear) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		PENDING.put(player.getUUID(), new Pending(placement.build().name(), placement.name(), clear));
		source.sendSuccess(() -> Component.literal("Remove placement ").append(VcsMessages.placement(placement))
			.append(clear ? ", emptying its " + placement.box().volume() + " blocks" : ", leaving its blocks standing")
			.append("? The build's versions are kept. Run ").append(ChatButtons.command("/vcs confirmUnplace")).append(" to proceed."), false);
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

		BuildPlacement placement = found.get();
		BuildBox box = placement.box();
		if (pending.clear()) {
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
			pending.clear() ? "emptying its box" : "leaving its blocks");

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
			.append(pending.clear() ? " and emptied its box" : "; its blocks were left standing")
			.append("; build ").append(VcsMessages.name(placement.build().name())).append(" keeps its " + placement.build().version()
				+ (placement.build().version() == 1 ? " version" : " versions")), false);
		return 1;
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
