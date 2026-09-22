package tony.mcvcs.network;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.ClientPlacement;

/**
 * Keeps each client informed of the placements in its world and of the one its player has selected, so it can
 * label every placement and draw the selected one's bounding box.
 * <p>
 * The client is brought up to date when the player joins, whenever {@link BuildRegistry#select} or
 * {@link BuildRegistry#deselect} runs for them, and, since builds are shared, for everyone whenever a build is
 * created, placed, committed or unplaced. Each placement carries the dimension its box is in, so changing
 * dimension needs no resend; the client draws each one only there.
 */
public final class BuildSync {
	private BuildSync() {
	}

	/** Registers the payload type and the events that resend the builds; must run on both sides. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(BuildsPayload.TYPE, BuildsPayload.STREAM_CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
	}

	/** Tells {@code player}'s client every placement in their world and which one they have selected, if the client has this mod. */
	public static void send(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, BuildsPayload.TYPE)) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		List<ClientPlacement> placements = BuildRegistry.allPlacements(server).stream().map(ClientPlacement::of).toList();
		Optional<String> selected = BuildRegistry.selected(player).map(BuildPlacement::label);
		ServerPlayNetworking.send(player, new BuildsPayload(placements, selected));
	}

	/** {@link #send Sends} to every player on {@code server}, for when the placements themselves have changed. */
	public static void broadcast(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player);
		}
	}
}
