package tony.mcvcs.client.project;

import java.util.List;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import tony.mcvcs.network.ProjectsPayload;
import tony.mcvcs.project.ClientProject;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of the builds in the world it is playing, as the server last sent them: every build, so each
 * can be labelled, and the one the player has selected, so its bounding box can be drawn.
 * <p>
 * The server sends the whole set again whenever any of it changes, see {@link tony.mcvcs.network.ProjectSync}, so
 * nothing here is ever edited in place. Each build carries the dimension its box is in, so a dimension change needs
 * neither a resend nor a reset; only leaving the server forgets the builds, and the next one sends its own on join.
 */
public final class ClientProjects {
	private static volatile State state = State.EMPTY;

	private ClientProjects() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ProjectsPayload.TYPE, (payload, context) -> state = State.of(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = State.EMPTY);
	}

	/** Every build in the world, sorted by name; empty until the server has sent them. */
	public static List<ClientProject> all() {
		return state.projects();
	}

	/** The build the player has selected, if any; always one of {@link #all()}. */
	public static @Nullable ClientProject selected() {
		return state.selected();
	}

	/** Both halves of one payload, so a frame never sees the builds of one payload with the selection of another. */
	private record State(List<ClientProject> projects, @Nullable ClientProject selected) {
		private static final State EMPTY = new State(List.of(), null);

		private static State of(ProjectsPayload payload) {
			ClientProject selected = payload.selected()
				.flatMap(name -> payload.projects().stream().filter(project -> project.name().equals(name)).findFirst())
				.orElse(null);
			return new State(List.copyOf(payload.projects()), selected);
		}
	}
}
