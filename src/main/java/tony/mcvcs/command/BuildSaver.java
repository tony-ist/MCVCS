package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraft.server.level.ServerLevel;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.BuildSync;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;

/**
 * Saves a build's box as one of its versions, the way {@code /vcs create}, {@code /vcs commit} and {@code /vcs expand}
 * all do: the box is copied into a schematic anchored at {@link #ORIGIN_CORNER} and written to {@link BuildStorage}.
 * Also holds what the copy keeps, see {@link #COPY_ENTITIES} and {@link #COPY_BIOMES}, which {@code /vcs checkout}
 * follows when it pastes a version back.
 */
public final class BuildSaver {
	/** Corner of the selection the schematic origin is anchored to. */
	public static final Corner ORIGIN_CORNER = Corner.TOP_NORTH_WEST;
	/**
	 * Extra offset applied on top of {@link #ORIGIN_CORNER}.
	 * <p>
	 * Pasting puts the origin at the player's feet, so anchoring straight to the top layer leaves the player standing
	 * inside it. Lifting the origin one block above the selection drops the whole build one block below the player.
	 */
	public static final BlockVector3 ORIGIN_OFFSET = BlockVector3.at(0, 1, 0);
	public static final boolean COPY_ENTITIES = false;
	public static final boolean COPY_BIOMES = false;

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

		public BlockVector3 of(Region region) {
			BlockVector3 min = region.getMinimumPoint();
			BlockVector3 max = region.getMaximumPoint();
			return BlockVector3.at(
				east ? max.x() : min.x(),
				top ? max.y() : min.y(),
				south ? max.z() : min.z()
			);
		}
	}

	private BuildSaver() {
	}

	/**
	 * Copies {@code box}, the world box of the placement the version is committed from, into a clipboard anchored at
	 * the configured origin, writes it as {@code build}'s version {@code version} and the build itself to
	 * {@link BuildStorage} and tells every client about the new state of the build.
	 * <p>
	 * The clipboard keeps the world coordinates it was copied from, which is what {@code /vcs load} and
	 * {@code //paste} go by, but they are the coordinates of one placement and say nothing about where the version
	 * belongs at another: that comes from the build's own {@link Build#extent extents}, see {@link BuildPlacer}.
	 */
	static Path save(Player actor, LocalSession session, Build build, int version, BuildBox box, ServerLevel level) throws WorldEditException, IOException {
		Region region = box.region(level);
		World world = region.getWorld();

		BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
		clipboard.setOrigin(ORIGIN_CORNER.of(region).add(ORIGIN_OFFSET));

		try (EditSession editSession = session.createEditSession(actor)) {
			ForwardExtentCopy copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
			copy.setCopyingEntities(COPY_ENTITIES);
			copy.setCopyingBiomes(COPY_BIOMES);
			Operations.complete(copy);
		}

		Path file = BuildStorage.save(build, version, clipboard);
		MCVCS.LOGGER.info("{} saved build '{}' v{} from {} at {}", actor.getName(), build.name(), version, world == null ? "a box" : "a box in " + world.getName(), file);
		// Builds are shared, so every client's list just changed; the caller's own selection is sent once it is updated.
		BuildSync.broadcast(level.getServer());
		return file;
	}
}
