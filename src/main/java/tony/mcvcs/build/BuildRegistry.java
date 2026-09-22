package tony.mcvcs.build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jspecify.annotations.Nullable;
import tony.mcvcs.MCVCS;
import tony.mcvcs.network.BuildSync;

/**
 * Looks up {@link Build}s by name and tracks which {@link BuildPlacement} each player has selected. {@code /vcs
 * create} and {@code /vcs place} select the placement they made, {@code /vcs select} picks an existing one,
 * {@code /vcs builds} lists them all, {@code /vcs deselect} drops the selection, and every command that works on a
 * build in the world acts on the selected placement.
 * <p>
 * A selection is a reference by name, so a commit by one player is seen by everyone who has a placement of that
 * build selected. Builds and selections live in the {@link BuildStorage} folder, not in any world save, but each
 * records the world it belongs to and everything here is scoped to the world the server runs, see
 * {@link Build#worldOf}. They are there whether or not the player's client has this mod. Only the server thread
 * touches this.
 */
public final class BuildRegistry {
	private BuildRegistry() {
	}

	/**
	 * Makes the placement called {@code placement} of {@code build}, which must already be saved and belong to the
	 * player's world, {@code player}'s selected placement there and tells their client, see {@link BuildSync}.
	 */
	public static void select(ServerPlayer player, Build build, String placement) throws IOException {
		MinecraftServer server = player.level().getServer();
		if (!build.isIn(server)) {
			throw new IllegalArgumentException("Build '" + build.name() + "' belongs to world '" + build.world() + "', not '" + Build.worldOf(server) + "'");
		}
		if (build.placement(placement).isEmpty()) {
			throw new IllegalArgumentException("Build '" + build.name() + "' has no placement '" + placement + "'");
		}
		BuildStorage.saveSelection(build.world(), player.getUUID(), new BuildStorage.Selection(build.name(), placement));
		BuildSync.send(player);
	}

	/** Makes {@code placement} {@code player}'s selected placement, see {@link #select(ServerPlayer, Build, String)}. */
	public static void select(ServerPlayer player, BuildPlacement placement) throws IOException {
		select(player, placement.build(), placement.name());
	}

	/** Leaves {@code player} with nothing selected in the world they are playing and tells their client. */
	public static void deselect(ServerPlayer player) throws IOException {
		BuildStorage.clearSelection(Build.worldOf(player.level().getServer()), player.getUUID());
		BuildSync.send(player);
	}

	/**
	 * The placement {@code player} has selected in the world they are playing, if any; a selection whose build folder
	 * is gone, whose placement has been unplaced or which now belongs to another world counts as none.
	 */
	public static Optional<BuildPlacement> selected(ServerPlayer player) {
		String world = Build.worldOf(player.level().getServer());
		try {
			Optional<BuildStorage.Selection> selection = BuildStorage.selection(world, player.getUUID());
			if (selection.isEmpty()) {
				return Optional.empty();
			}
			return BuildStorage.find(world, selection.get().build())
				.flatMap(build -> BuildPlacement.of(build, selection.get().placement()));
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read the selection of {}", player.getGameProfile().name(), e);
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

	/** The build whose name is {@code name} up to letter case, in whichever world it belongs to, if one exists. */
	public static Optional<Build> findInAnyWorldIgnoringCase(String name) {
		try {
			return BuildStorage.findInAnyWorldIgnoringCase(name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read build named like '{}'", name, e);
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

	/** Every placement of every build in the world {@code server} runs, sorted by build then placement name. */
	public static List<BuildPlacement> allPlacements(MinecraftServer server) {
		List<BuildPlacement> placements = new ArrayList<>();
		for (Build build : all(server)) {
			placements.addAll(BuildPlacement.allOf(build));
		}
		return List.copyOf(placements);
	}

	/** Names of all builds in the world {@code server} runs, sorted. */
	public static List<String> names(MinecraftServer server) {
		return all(server).stream().map(Build::name).toList();
	}

	/**
	 * The placement whose box shares a block with {@code box} in {@code dimension}, if there is one; no two
	 * placements may ever overlap, so this is what {@code /vcs create}, {@code /vcs place}, {@code /vcs expand} and
	 * {@code /vcs checkout} refuse on.
	 *
	 * @param exclude the placement being moved or grown, which does not count as overlapping itself
	 */
	public static Optional<BuildPlacement> overlapping(MinecraftServer server, ResourceKey<Level> dimension, BuildBox box, @Nullable BuildPlacement exclude) {
		return allPlacements(server).stream()
			.filter(other -> exclude == null || !other.label().equals(exclude.label()))
			.filter(other -> other.dimension().equals(dimension) && other.box().intersects(box))
			.findFirst();
	}

	/** The placement of any build whose box holds {@code pos} in {@code dimension}, which at most one can, see {@link #overlapping}. */
	public static Optional<BuildPlacement> at(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
		return allPlacements(server).stream()
			.filter(placement -> placement.dimension().equals(dimension) && placement.box().contains(pos))
			.findFirst();
	}
}
