package tony.mcvcs.client.diff;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.diff.BlockChange;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.diff.ChangeKind;
import org.jspecify.annotations.Nullable;

/**
 * Turns the changed blocks of a {@link BuildDiff} into as few boxes as reasonably possible, so a diff touching
 * thousands of blocks, such as a whole build rebuilt, is drawn as a handful of gizmos per frame instead of one per
 * block. Blocks are merged only with neighbours changed the same way, so each box keeps a single color.
 * <p>
 * Greedy: runs along z are extended into slabs along y and then into boxes along x, each as far as every block in the
 * way has the same kind of change. The result is not the minimal cover, but it is deterministic and linear in the
 * box's volume, and every changed block is in exactly one box.
 */
public final class DiffHighlights {
	private DiffHighlights() {
	}

	/**
	 * A box of blocks that all differ the same way.
	 *
	 * @param aabb the blocks covered, from the min corner of the first to the max corner of the last
	 * @param kind how every block in the box differs
	 */
	public record Highlight(AABB aabb, ChangeKind kind) {
	}

	/** The boxes covering every change of {@code diff}, in the order their first blocks come in {@link BuildBox} order. */
	public static List<Highlight> merge(BuildDiff diff) {
		BuildBox box = diff.box();
		int sizeX = box.sizeX();
		int sizeY = box.sizeY();
		int sizeZ = box.sizeZ();
		// Same layout as BuildBox.index, so a change's box index is its slot here.
		@Nullable ChangeKind[] kinds = new ChangeKind[Math.toIntExact(box.volume())];
		for (BlockChange change : diff.changes()) {
			kinds[box.index(change.pos().getX(), change.pos().getY(), change.pos().getZ())] = change.kind();
		}

		List<Highlight> highlights = new ArrayList<>();
		BlockPos min = box.min();
		for (int x = 0; x < sizeX; x++) {
			for (int y = 0; y < sizeY; y++) {
				for (int z = 0; z < sizeZ; z++) {
					ChangeKind kind = kinds[(x * sizeY + y) * sizeZ + z];
					if (kind == null) {
						continue;
					}

					int depth = 1;
					while (z + depth < sizeZ && kinds[(x * sizeY + y) * sizeZ + z + depth] == kind) {
						depth++;
					}
					int height = 1;
					while (y + height < sizeY && rowIs(kinds, sizeY, sizeZ, x, y + height, z, depth, kind)) {
						height++;
					}
					int width = 1;
					while (x + width < sizeX && slabIs(kinds, sizeY, sizeZ, x + width, y, z, height, depth, kind)) {
						width++;
					}

					for (int dx = 0; dx < width; dx++) {
						for (int dy = 0; dy < height; dy++) {
							for (int dz = 0; dz < depth; dz++) {
								kinds[((x + dx) * sizeY + y + dy) * sizeZ + z + dz] = null;
							}
						}
					}
					highlights.add(new Highlight(new AABB(
						min.getX() + x, min.getY() + y, min.getZ() + z,
						min.getX() + x + width, min.getY() + y + height, min.getZ() + z + depth
					), kind));
					// The run along z is now cleared, so the scan can skip past it.
					z += depth - 1;
				}
			}
		}
		return List.copyOf(highlights);
	}

	/** Whether the {@code depth} blocks from {@code (x, y, z)} along z are all changed as {@code kind}. */
	private static boolean rowIs(@Nullable ChangeKind[] kinds, int sizeY, int sizeZ, int x, int y, int z, int depth, ChangeKind kind) {
		int start = (x * sizeY + y) * sizeZ + z;
		for (int dz = 0; dz < depth; dz++) {
			if (kinds[start + dz] != kind) {
				return false;
			}
		}
		return true;
	}

	/** Whether the {@code height} rows from {@code (x, y, z)} along y, each {@code depth} deep, are all changed as {@code kind}. */
	private static boolean slabIs(@Nullable ChangeKind[] kinds, int sizeY, int sizeZ, int x, int y, int z, int height, int depth, ChangeKind kind) {
		for (int dy = 0; dy < height; dy++) {
			if (!rowIs(kinds, sizeY, sizeZ, x, y + dy, z, depth, kind)) {
				return false;
			}
		}
		return true;
	}
}
