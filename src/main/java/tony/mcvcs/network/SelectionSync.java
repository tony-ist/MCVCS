package tony.mcvcs.network;

import java.util.Optional;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.project.ProjectRegistry;
import tony.mcvcs.project.SelectedProject;

/**
 * Keeps each client informed of the project its player has selected, so it can draw the project's bounding box.
 * <p>
 * Selection is per world, so the client is brought up to date when the player joins, whenever they change
 * dimension and whenever {@link ProjectRegistry#select} runs.
 */
public final class SelectionSync {
	private SelectionSync() {
	}

	/** Registers the payload type and the events that resend the selection; must run on both sides. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(SelectedProjectPayload.TYPE, SelectedProjectPayload.STREAM_CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> send(player));
	}

	/** Tells {@code player}'s client which project is selected in the world they are in, if the client has this mod. */
	public static void send(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, SelectedProjectPayload.TYPE)) {
			return;
		}

		Optional<SelectedProject> selected = ProjectRegistry.selected(player).map(project -> SelectedProject.of(project, player.level().dimension()));
		ServerPlayNetworking.send(player, new SelectedProjectPayload(selected));
	}
}
