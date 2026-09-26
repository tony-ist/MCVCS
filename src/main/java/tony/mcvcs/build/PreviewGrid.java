package tony.mcvcs.build;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;

/**
 * The blocks the builds overlay previews a version with: the version itself when it is small, or a coarser grid of it
 * when it is big. A preview is a few dozen pixels across, so a build hundreds of blocks long gets less than a pixel
 * per block and loses nothing to being sampled; what it gains is a mesh and a download a fraction of the size.
 * <p>
 * A version whose longest side is over {@link #MAX_SIDE} blocks is cut into cubes of {@link #scaleFor scale} blocks a
 * side, and each cube becomes one block of the grid. The grid starts at {@code (0, 0, 0)}; its blocks are
 * {@code scale} times as big as the version's, and the cubes along its far edges may reach past the version, where
 * there is only air.
 */
public final class PreviewGrid {
	/** Longest side, in blocks, a preview grid may have. */
	public static final int MAX_SIDE = 128;

	private PreviewGrid() {
	}

	/** How many blocks of a version of extent {@code extent} each block of its preview grid stands for along each side. */
	public static int scaleFor(BuildBox extent) {
		int longest = Math.max(extent.sizeX(), Math.max(extent.sizeY(), extent.sizeZ()));
		return Math.max(1, ceilDiv(longest, MAX_SIDE));
	}

	/** The grid a version of extent {@code extent} is previewed with at {@code scale}, from {@code (0, 0, 0)}. */
	public static BuildBox grid(BuildBox extent, int scale) {
		return new BuildBox(BlockPos.ZERO, new BlockPos(
			ceilDiv(extent.sizeX(), scale) - 1, ceilDiv(extent.sizeY(), scale) - 1, ceilDiv(extent.sizeZ(), scale) - 1));
	}

	/**
	 * The blocks of {@code clipboard}, a version of extent {@code extent}, sampled onto its {@link #grid} at
	 * {@code scale}. Each block of the grid takes a block of its cube that would be drawn, preferring one that fills
	 * its whole space, so a wall stays a wall however thin and redstone dust running over a floor does not hide it.
	 * A cube with nothing to draw in it is air. Block entity data is left out; a preview does not show it.
	 *
	 * @throws IllegalArgumentException if the clipboard is not the size of {@code extent}
	 */
	public static BoxSnapshot sample(Clipboard clipboard, BuildBox extent, int scale) {
		BuildBox region = BuildBox.of(clipboard.getRegion());
		if (region.sizeX() != extent.sizeX() || region.sizeY() != extent.sizeY() || region.sizeZ() != extent.sizeZ()) {
			throw new IllegalArgumentException("Schematic is " + region.sizeX() + "x" + region.sizeY() + "x" + region.sizeZ()
				+ " but the version is " + extent.sizeX() + "x" + extent.sizeY() + "x" + extent.sizeZ());
		}

		FabricAdapter adapter = FabricAdapter.get();
		// Schematics repeat a handful of block states millions of times; converting each once is enough.
		Map<com.sk89q.worldedit.world.block.BlockState, BlockState> converted = new HashMap<>();
		BuildBox grid = grid(extent, scale);
		BlockState[] blocks = new BlockState[Math.toIntExact(grid.volume())];
		BlockPos min = region.min();

		for (int gx = 0; gx < grid.sizeX(); gx++) {
			for (int gy = 0; gy < grid.sizeY(); gy++) {
				for (int gz = 0; gz < grid.sizeZ(); gz++) {
					BlockState chosen = Blocks.AIR.defaultBlockState();
					cube:
					for (int dx = 0; dx < scale; dx++) {
						int x = gx * scale + dx;
						if (x >= extent.sizeX()) {
							break;
						}
						for (int dy = 0; dy < scale; dy++) {
							int y = gy * scale + dy;
							if (y >= extent.sizeY()) {
								break;
							}
							for (int dz = 0; dz < scale; dz++) {
								int z = gz * scale + dz;
								if (z >= extent.sizeZ()) {
									break;
								}
								BlockState state = converted.computeIfAbsent(
									clipboard.getBlock(BlockVector3.at(min.getX() + x, min.getY() + y, min.getZ() + z)),
									adapter::toNativeBlockState);
								// A version small enough to go whole keeps every block as it is.
								if (scale == 1 || state.isSolidRender()) {
									chosen = state;
									break cube;
								}
								if (chosen.isAir() && state.getRenderShape() == RenderShape.MODEL) {
									chosen = state;
								}
							}
						}
					}
					blocks[grid.index(gx, gy, gz)] = chosen;
				}
			}
		}
		return new BoxSnapshot(grid, blocks, Int2ObjectMaps.emptyMap());
	}

	private static int ceilDiv(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}
}
