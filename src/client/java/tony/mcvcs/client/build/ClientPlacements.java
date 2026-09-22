package tony.mcvcs.client.build;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import tony.mcvcs.network.BuildsPayload;
import tony.mcvcs.build.ClientPlacement;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of the placements in the world it is playing, as the server last sent them: every placement of
 * every build, so each can be labelled, and the one the player has selected, so its bounding box can be drawn.
 * <p>
 * The server sends the whole set again whenever any of it changes, see {@link tony.mcvcs.network.BuildSync}, so
 * nothing here is ever edited in place. Each placement carries the dimension its box is in, so a dimension change
 * needs neither a resend nor a reset; only leaving the server forgets them, and the next one sends its own on join.
 */
public final class ClientPlacements {
	private static volatile State state = State.EMPTY;

	private ClientPlacements() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BuildsPayload.TYPE, (payload, context) -> state = State.of(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = State.EMPTY);
	}

	/** Every placement in the world, sorted by build then placement name; empty until the server has sent them. */
	public static List<ClientPlacement> all() {
		return state.placements();
	}

	/** The placement the player has selected, if any; always one of {@link #all()}. */
	public static @Nullable ClientPlacement selected() {
		return state.selected();
	}

	/** Both halves of one payload, so a frame never sees the placements of one payload with the selection of another. */
	private record State(List<ClientPlacement> placements, @Nullable ClientPlacement selected) {
		private static final State EMPTY = new State(List.of(), null);

		private static State of(BuildsPayload payload) {
			ClientPlacement selected = payload.selected()
				.flatMap(label -> payload.placements().stream().filter(placement -> placement.label().equals(label)).findFirst())
				.orElse(null);
			return new State(List.copyOf(payload.placements()), selected);
		}
	}
}
