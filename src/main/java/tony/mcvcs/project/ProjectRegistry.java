package tony.mcvcs.project;

import java.util.List;
import java.util.Optional;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.network.SelectionSync;

/**
 * Tracks every {@link Project}, per world and name, and which one each player has selected in each world.
 * {@code /vcs create} and {@code /vcs commit} record the project they wrote and select it, {@code /vcs select}
 * picks an existing one, and {@code /vcs commit} and {@code /vcs preview} operate on the selected one.
 * <p>
 * A selection is a reference by name, so a commit by one player is seen by everyone who has that project selected.
 * Projects and selections are saved with the world, see {@link ProjectData}, so they survive server restarts and
 * are there whether or not the player's client has this mod. Only the server thread touches this.
 */
public final class ProjectRegistry {
	private ProjectRegistry() {
	}

	/**
	 * Records {@code project} as the current state of the build with its name in the world {@code player} is in,
	 * makes it the player's selected project there, writes both to disk and tells their client about it.
	 */
	public static void select(ServerPlayer player, Project project) {
		ServerLevel level = player.level();
		ProjectData data = ProjectData.get(level);
		data.put(project);
		data.select(player.getUUID(), project.name());
		level.getDataStorage().scheduleSave();
		SelectionSync.send(player);
	}

	/** The project {@code player} has selected in the world they are currently in, if any. */
	public static Optional<Project> selected(ServerPlayer player) {
		return ProjectData.get(player.level()).selected(player.getUUID());
	}

	/** The project called {@code name} in {@code level}, if one exists. */
	public static Optional<Project> find(ServerLevel level, String name) {
		return ProjectData.get(level).find(name);
	}

	/** Names of all projects in {@code level}, sorted. */
	public static List<String> names(ServerLevel level) {
		return ProjectData.get(level).names();
	}
}
