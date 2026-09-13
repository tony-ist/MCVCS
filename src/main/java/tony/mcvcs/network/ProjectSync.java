package tony.mcvcs.network;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.project.ClientProject;
import tony.mcvcs.project.Project;
import tony.mcvcs.project.ProjectRegistry;

/**
 * Keeps each client informed of the builds in its world and of the one its player has selected, so it can label
 * every build and draw the selected one's bounding box.
 * <p>
 * The client is brought up to date when the player joins, whenever {@link ProjectRegistry#select} or
 * {@link ProjectRegistry#deselect} runs for them, and, since builds are shared, for everyone whenever a build is
 * created or committed. Each build carries the dimension its box is in, so changing dimension needs no resend; the
 * client draws each one only there.
 */
public final class ProjectSync {
	private ProjectSync() {
	}

	/** Registers the payload type and the events that resend the builds; must run on both sides. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ProjectsPayload.TYPE, ProjectsPayload.STREAM_CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
	}

	/** Tells {@code player}'s client every build in their world and which one they have selected, if the client has this mod. */
	public static void send(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, ProjectsPayload.TYPE)) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		List<ClientProject> projects = ProjectRegistry.all(server).stream().map(ClientProject::of).toList();
		Optional<String> selected = ProjectRegistry.selected(player).map(Project::name);
		ServerPlayNetworking.send(player, new ProjectsPayload(projects, selected));
	}

	/** {@link #send Sends} to every player on {@code server}, for when the set of builds itself has changed. */
	public static void broadcast(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player);
		}
	}
}
