package tony.mcvcs.project;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import tony.mcvcs.network.SelectionSync;

/**
 * Tracks which {@link Project} each player has selected, per world. {@code /vcs create} selects the project it
 * creates and {@code /vcs commit} operates on the selected one.
 * <p>
 * State lives in memory for the lifetime of the server and is only touched from the server thread.
 */
public final class ProjectRegistry {
	private record Key(UUID player, ResourceKey<Level> world) {
		static Key of(ServerPlayer player) {
			return new Key(player.getUUID(), player.level().dimension());
		}
	}

	private static final Map<Key, Project> SELECTED = new HashMap<>();

	private ProjectRegistry() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SELECTED.clear());
	}

	/**
	 * Makes {@code project} the selected project for {@code player} in the world they are currently in and tells
	 * their client about it.
	 */
	public static void select(ServerPlayer player, Project project) {
		SELECTED.put(Key.of(player), project);
		SelectionSync.send(player);
	}

	/** The project {@code player} has selected in the world they are currently in, if any. */
	public static Optional<Project> selected(ServerPlayer player) {
		return Optional.ofNullable(SELECTED.get(Key.of(player)));
	}
}
