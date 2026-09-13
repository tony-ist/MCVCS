package tony.mcvcs.build;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.network.BuildSync;

/**
 * Looks up {@link Build}s by name and tracks which one each player has selected. {@code /vcs create} and
 * {@code /vcs commit} select the build they wrote, {@code /vcs select} picks an existing one, {@code /vcs builds}
 * lists them all, {@code /vcs deselect} drops the selection, and {@code /vcs commit}, {@code /vcs preview},
 * {@code /vcs load} and {@code /vcs diff} operate on the selected one.
 * <p>
 * A selection is a reference by name, so a commit by one player is seen by everyone who has that build selected.
 * Builds and selections live in the {@link BuildStorage} folder, not in any world save, but each records the
 * world it belongs to and everything here is scoped to the world the server runs, see {@link Build#worldOf}. They
 * are there whether or not the player's client has this mod. Only the server thread touches this.
 */
public final class BuildRegistry {
	private BuildRegistry() {
	}

	/**
	 * Makes {@code build}, which must already be saved and belong to the player's world, {@code player}'s selected
	 * build there and tells their client, see {@link BuildSync}.
	 */
	public static void select(ServerPlayer player, Build build) throws IOException {
		MinecraftServer server = player.level().getServer();
		if (!build.isIn(server)) {
			throw new IllegalArgumentException("Build '" + build.name() + "' belongs to world '" + build.world() + "', not '" + Build.worldOf(server) + "'");
		}
		BuildStorage.saveSelection(build.world(), player.getUUID(), build.name());
		BuildSync.send(player);
	}

	/** Leaves {@code player} with no selected build in the world they are playing and tells their client. */
	public static void deselect(ServerPlayer player) throws IOException {
		BuildStorage.clearSelection(Build.worldOf(player.level().getServer()), player.getUUID());
		BuildSync.send(player);
	}

	/**
	 * The build {@code player} has selected in the world they are playing, if any; a selection whose build
	 * folder is gone or now belongs to another world counts as none.
	 */
	public static Optional<Build> selected(ServerPlayer player) {
		String world = Build.worldOf(player.level().getServer());
		try {
			Optional<String> name = BuildStorage.selection(world, player.getUUID());
			return name.isPresent() ? BuildStorage.find(world, name.get()) : Optional.empty();
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read the selected build of {}", player.getGameProfile().name(), e);
			return Optional.empty();
		}
	}

	/** The build called {@code name} in the world {@code server} runs, if one exists. */
	public static Optional<Build> find(MinecraftServer server, String name) {
		try {
			return BuildStorage.find(Build.worldOf(server), name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read build '{}'", name, e);
			return Optional.empty();
		}
	}

	/** The build called {@code name} in whichever world it belongs to, if one exists. */
	public static Optional<Build> findInAnyWorld(String name) {
		try {
			return BuildStorage.findInAnyWorld(name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read build '{}'", name, e);
			return Optional.empty();
		}
	}

	/** All builds in the world {@code server} runs, sorted by name. */
	public static List<Build> all(MinecraftServer server) {
		try {
			return BuildStorage.all(Build.worldOf(server));
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to list builds", e);
			return List.of();
		}
	}

	/** Names of all builds in the world {@code server} runs, sorted. */
	public static List<String> names(MinecraftServer server) {
		return all(server).stream().map(Build::name).toList();
	}
}
