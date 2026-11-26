package tony.mcvcs.build;

import net.minecraft.core.BlockPos;

/**
 * One version of a build as it is kept on disk, see {@link SpongeSchematic}: every block of the box it was committed
 * from, at the world coordinates it was copied at, and the origin it is anchored to.
 * <p>
 * The coordinates are those of the placement the version was committed from and mean nothing at another one, so
 * where a version goes at a placement comes from the build's extents instead, see {@link BuildPlacement#boxOf}; only
 * the shape of {@link #box} is used there. The origin is what WorldEdit's {@code //paste} goes by when someone loads
 * the file with {@code //schem load}.
 *
 * @param origin the point the schematic is anchored to, in world coordinates
 * @param blocks every block of the box, at the world coordinates it was copied at
 */
public record Schematic(BlockPos origin, BoxSnapshot blocks) {
	/** The box the blocks were copied from, in world coordinates. */
	public BuildBox box() {
		return blocks.box();
	}
}
