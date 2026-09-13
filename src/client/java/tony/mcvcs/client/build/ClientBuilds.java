package tony.mcvcs.client.build;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import tony.mcvcs.network.BuildsPayload;
import tony.mcvcs.build.ClientBuild;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of the builds in the world it is playing, as the server last sent them: every build, so each
 * can be labelled, and the one the player has selected, so its bounding box can be drawn.
 * <p>
 * The server sends the whole set again whenever any of it changes, see {@link tony.mcvcs.network.BuildSync}, so
 * nothing here is ever edited in place. Each build carries the dimension its box is in, so a dimension change needs
 * neither a resend nor a reset; only leaving the server forgets the builds, and the next one sends its own on join.
 */
public final class ClientBuilds {
	private static volatile State state = State.EMPTY;

	private ClientBuilds() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BuildsPayload.TYPE, (payload, context) -> state = State.of(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = State.EMPTY);
	}

	/** Every build in the world, sorted by name; empty until the server has sent them. */
	public static List<ClientBuild> all() {
		return state.builds();
	}

	/** The build the player has selected, if any; always one of {@link #all()}. */
	public static @Nullable ClientBuild selected() {
		return state.selected();
	}

	/** Both halves of one payload, so a frame never sees the builds of one payload with the selection of another. */
	private record State(List<ClientBuild> builds, @Nullable ClientBuild selected) {
		private static final State EMPTY = new State(List.of(), null);

		private static State of(BuildsPayload payload) {
			ClientBuild selected = payload.selected()
				.flatMap(name -> payload.builds().stream().filter(build -> build.name().equals(name)).findFirst())
				.orElse(null);
			return new State(List.copyOf(payload.builds()), selected);
		}
	}
}
