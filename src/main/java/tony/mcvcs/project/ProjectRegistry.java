package tony.mcvcs.project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import tony.mcvcs.network.SelectionSync;

/**
 * Tracks every {@link Project} created this server run, per world and name, and which one each player has selected
 * in each world. {@code /vcs create} and {@code /vcs commit} record the project they wrote and select it,
 * {@code /vcs select} picks an existing one, and {@code /vcs commit} and {@code /vcs preview} operate on the selected
 * one.
 * <p>
 * A selection is a reference by name, so a commit by one player is seen by everyone who has that project selected.
 * State lives in memory for the lifetime of the server and is only touched from the server thread.
 */
public final class ProjectRegistry {
	private record Key(UUID player, ResourceKey<Level> world) {
		static Key of(ServerPlayer player) {
			return new Key(player.getUUID(), player.level().dimension());
		}
	}

	/** Name of the project each player has selected, per world. */
	private static final Map<Key, String> SELECTED = new HashMap<>();
	/** Every known project, by world and name. */
	private static final Map<ResourceKey<Level>, Map<String, Project>> PROJECTS = new HashMap<>();

	private ProjectRegistry() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SELECTED.clear();
			PROJECTS.clear();
		});
	}

	/**
	 * Records {@code project} as the current state of the build with its name in the world {@code player} is in,
	 * makes it the player's selected project there and tells their client about it.
	 */
	public static void select(ServerPlayer player, Project project) {
		ResourceKey<Level> world = player.level().dimension();
		PROJECTS.computeIfAbsent(world, key -> new HashMap<>()).put(project.name(), project);
		SELECTED.put(Key.of(player), project.name());
		SelectionSync.send(player);
	}

	/** The project {@code player} has selected in the world they are currently in, if any. */
	public static Optional<Project> selected(ServerPlayer player) {
		return Optional.ofNullable(SELECTED.get(Key.of(player))).flatMap(name -> find(player.level().dimension(), name));
	}

	/** The project called {@code name} in {@code world}, if one was created this server run. */
	public static Optional<Project> find(ResourceKey<Level> world, String name) {
		return Optional.ofNullable(PROJECTS.getOrDefault(world, Map.of()).get(name));
	}

	/** Names of all projects in {@code world}, sorted. */
	public static List<String> names(ResourceKey<Level> world) {
		return PROJECTS.getOrDefault(world, Map.of()).keySet().stream().sorted().toList();
	}
}
