package tony.mcvcs.command;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;

/**
 * {@code /vcs delete <buildname>}: asks the player to confirm deleting the build; nothing is touched yet.
 * {@code /vcs confirmDelete} then removes the build's folder with every version in it and every player's selection of
 * it. The confirmation is remembered until it is used, replaced by another {@code /vcs delete}, or the player leaves.
 */
public final class VcsCommandDelete {
	/**
	 * The build each player's last {@code /vcs delete} asked to delete and {@code /vcs confirmDelete} will act on,
	 * by player UUID. Only the server thread touches this; a player's entry goes when they leave.
	 */
	private static final Map<UUID, Build> PENDING_DELETES = new HashMap<>();

	static final VcsHelp HELP = new VcsHelp("delete", "/vcs delete <buildname>",
		"delete a build, once you confirm",
		"Asks you to confirm deleting the build. Nothing is deleted until you run /vcs confirmDelete; the request is forgotten if you leave the server first, and a second /vcs delete replaces it.");
	static final VcsHelp CONFIRM_HELP = new VcsHelp("confirmDelete", "/vcs confirmDelete",
		"delete the build your last /vcs delete named",
		"Deletes the build your last /vcs delete named: its folder with every version in it is removed and anyone who had it selected loses that selection. This cannot be undone.");

	private VcsCommandDelete() {
	}

	/** Forgets a player's pending confirmation when they leave. */
	static void register() {
		// A confirmation left behind by a player who logged out must not delete anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING_DELETES.remove(handler.player.getUUID()));
	}

	/** Only asks for confirmation; {@link #confirm} does the deleting. */
	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world"));
			return 0;
		}

		PENDING_DELETES.put(player.getUUID(), build.get());
		source.sendSuccess(() -> Component.literal("Delete build ").append(VcsMessages.name(buildName)).append("? This cannot be undone, all versions will be lost! Run ").append(ChatButtons.command("/vcs confirmDelete")).append(" to proceed."), false);
		return 1;
	}

	/** {@code /vcs confirmDelete}. */
	static int confirm(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		Build build = PENDING_DELETES.remove(player.getUUID());
		if (build == null) {
			source.sendFailure(Component.literal("Nothing to confirm; run ").append(ChatButtons.template("/vcs delete <buildname>")).append(" first"));
			return 0;
		}
		// The build may have gone, or the player moved to another world, since they asked.
		if (!build.isIn(server) || BuildRegistry.find(server, build.name()).isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(build.name())).append(" in this world"));
			return 0;
		}

		// Anyone previewing or diffing the build is looking at a version that is about to disappear.
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (BuildRegistry.selected(other).filter(selected -> selected.name().equals(build.name())).isEmpty()) {
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
		MCVCS.LOGGER.info("{} deleted build '{}' with {} versions", player.getGameProfile().name(), build.name(), build.version());
		// The build and any selection of it are gone, so every client's list and possibly its selection changed.
		BuildSync.broadcast(server);

		source.sendSuccess(() -> Component.literal("Deleted build ").append(VcsMessages.name(build.name())).append(" and its " + build.version() + (build.version() == 1 ? " version" : " versions")), false);
		return 1;
	}
}
