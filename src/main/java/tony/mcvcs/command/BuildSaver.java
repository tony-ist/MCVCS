package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.Schematic;
import tony.mcvcs.network.BuildSync;

/**
 * Saves a build's box as one of its versions, the way {@code /vcs create}, {@code /vcs save} and {@code /vcs fit}
 * all do: the blocks of the box are copied into a schematic anchored at {@link #ORIGIN_CORNER} and written to
 * {@link BuildStorage}. Only blocks and their block entities are kept; entities and biomes are not.
 */
public final class BuildSaver {
	/** Corner of the box the schematic origin is anchored to. */
	public static final Corner ORIGIN_CORNER = Corner.TOP_NORTH_WEST;
	/**
	 * Extra offset applied on top of {@link #ORIGIN_CORNER}.
	 * <p>
	 * WorldEdit's {@code //paste} puts the origin at the player's feet, so anchoring straight to the top layer would
	 * leave the player standing inside it. Lifting the origin one block above the box drops the whole build one block
	 * below the player, the same place {@code /vcs place} starts a copy at.
	 */
	public static final BlockPos ORIGIN_OFFSET = new BlockPos(0, 1, 0);

	/** A corner of a cuboid; north is -Z, west is -X, bottom is -Y. */
	public enum Corner {
		BOTTOM_NORTH_WEST(false, false, false),
		BOTTOM_NORTH_EAST(true, false, false),
		BOTTOM_SOUTH_WEST(false, false, true),
		BOTTOM_SOUTH_EAST(true, false, true),
		TOP_NORTH_WEST(false, true, false),
		TOP_NORTH_EAST(true, true, false),
		TOP_SOUTH_WEST(false, true, true),
		TOP_SOUTH_EAST(true, true, true);

		private final boolean east;
		private final boolean top;
		private final boolean south;

		Corner(boolean east, boolean top, boolean south) {
			this.east = east;
			this.top = top;
			this.south = south;
		}

		public BlockPos of(BuildBox box) {
			BlockPos min = box.min();
			BlockPos max = box.max();
			return new BlockPos(
				east ? max.getX() : min.getX(),
				top ? max.getY() : min.getY(),
				south ? max.getZ() : min.getZ()
			);
		}
	}

	private BuildSaver() {
	}

	/**
	 * Copies {@code box}, the world box of the placement the version is committed from, into a schematic anchored at
	 * the configured origin, writes it as {@code build}'s version {@code version} and the build itself to
	 * {@link BuildStorage} and tells every client about the new state of the build.
	 * <p>
	 * The schematic keeps the world coordinates it was copied from, but they are the coordinates of one placement and
	 * say nothing about where the version belongs at another: that comes from the build's own
	 * {@link Build#extent extents}, see {@link BuildPlacer}.
	 */
	static Path save(ServerPlayer player, Build build, int version, BuildBox box, ServerLevel level) throws IOException {
		Schematic schematic = new Schematic(ORIGIN_CORNER.of(box).offset(ORIGIN_OFFSET), BoxSnapshot.ofLevel(box, level));
		Path file = BuildStorage.save(build, version, schematic);
		MCVCS.LOGGER.info("{} saved build '{}' v{} from {} in {} at {}", player.getGameProfile().name(), build.name(), version, box, level.dimension().identifier(), file);
		// Builds are shared, so every client's list just changed; the caller's own selection is sent once it is updated.
		BuildSync.broadcast(level.getServer());
		return file;
	}
}
