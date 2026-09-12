package tony.mcvcs.project;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.network.SelectionSync;

/**
 * Looks up {@link Project}s by name and tracks which one each player has selected. {@code /vcs create} and
 * {@code /vcs commit} select the project they wrote, {@code /vcs select} picks an existing one, {@code /vcs deselect}
 * drops the selection, and {@code /vcs commit} and {@code /vcs preview} operate on the selected one.
 * <p>
 * A selection is a reference by name, so a commit by one player is seen by everyone who has that project selected.
 * Projects and selections live in the {@link ProjectStorage} folder, not in any world save, but each records the
 * world it belongs to and everything here is scoped to the world the server runs, see {@link Project#worldOf}. They
 * are there whether or not the player's client has this mod. Only the server thread touches this.
 */
public final class ProjectRegistry {
	private ProjectRegistry() {
	}

	/**
	 * Makes {@code project}, which must already be saved and belong to the player's world, {@code player}'s selected
	 * project there and tells their client.
	 */
	public static void select(ServerPlayer player, Project project) throws IOException {
		MinecraftServer server = player.level().getServer();
		if (!project.isIn(server)) {
			throw new IllegalArgumentException("Build '" + project.name() + "' belongs to world '" + project.world() + "', not '" + Project.worldOf(server) + "'");
		}
		ProjectStorage.saveSelection(project.world(), player.getUUID(), project.name());
		SelectionSync.send(player);
	}

	/** Leaves {@code player} with no selected project in the world they are playing and tells their client. */
	public static void deselect(ServerPlayer player) throws IOException {
		ProjectStorage.clearSelection(Project.worldOf(player.level().getServer()), player.getUUID());
		SelectionSync.send(player);
	}

	/**
	 * The project {@code player} has selected in the world they are playing, if any; a selection whose project
	 * folder is gone or now belongs to another world counts as none.
	 */
	public static Optional<Project> selected(ServerPlayer player) {
		String world = Project.worldOf(player.level().getServer());
		try {
			Optional<String> name = ProjectStorage.selection(world, player.getUUID());
			return name.isPresent() ? ProjectStorage.find(world, name.get()) : Optional.empty();
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read the selected project of {}", player.getGameProfile().name(), e);
			return Optional.empty();
		}
	}

	/** The project called {@code name} in the world {@code server} runs, if one exists. */
	public static Optional<Project> find(MinecraftServer server, String name) {
		try {
			return ProjectStorage.find(Project.worldOf(server), name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read project '{}'", name, e);
			return Optional.empty();
		}
	}

	/** The project called {@code name} in whichever world it belongs to, if one exists. */
	public static Optional<Project> findInAnyWorld(String name) {
		try {
			return ProjectStorage.findInAnyWorld(name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read project '{}'", name, e);
			return Optional.empty();
		}
	}

	/** Names of all projects in the world {@code server} runs, sorted. */
	public static List<String> names(MinecraftServer server) {
		try {
			return ProjectStorage.names(Project.worldOf(server));
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to list projects", e);
			return List.of();
		}
	}
}
