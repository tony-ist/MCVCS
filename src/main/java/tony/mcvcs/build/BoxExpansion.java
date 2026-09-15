package tony.mcvcs.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A box grown until nothing but air surrounds it, so that whatever was built out past the edges of a build's box
 * ends up inside it: the box, not the blocks, is the input, and {@link #to()} is the smallest box that contains
 * the original one and is enclosed by air on all sides, corners and edges included.
 * <p>
 * The box grows step by step: each step looks at the one-block shell around the box and, if any block in it is not
 * air, widens the box just enough to take those blocks in; then looks again. Anything touching the box, even
 * only at a corner, pulls the box out to cover it, and anything touching <em>that</em> pulls it further, so two
 * stone blocks at {@code (0, 0, 0)} and {@code (1, 1, 1)} in an otherwise empty world expand a box on either one
 * to cover both. The same pull is why a build standing on the ground would swallow the ground: builds are meant to
 * hover in the air. A block that is air by {@link net.minecraft.world.level.block.state.BlockState#isAir}, void air
 * outside the world's height included, is the only thing that stops the growth; water, grass and the like do not.
 * <p>
 * Growth stops early rather than exceed {@link #MAX_VOLUME} blocks: a step that would make the box larger than
 * that is not taken, {@link #to()} is the box as it was before the step and {@link #enclosed()} is false. A box that
 * is already over the limit is left as it is. On the server the scan loads any chunk the shell touches.
 *
 * @param from     the box the expansion started from
 * @param to       the box it ended with, {@code from} or larger
 * @param enclosed whether the shell around {@code to} is all air, which is false only when {@link #MAX_VOLUME} stopped
 *                 the growth before it was
 */
public record BoxExpansion(BuildBox from, BuildBox to, boolean enclosed) {
	/** The most blocks a box may cover after expansion; a step that would go past this is not taken. */
	public static final long MAX_VOLUME = 1_000_000;

	/** Expands {@code box} in {@code level} as described on this class. */
	public static BoxExpansion of(BuildBox box, Level level) {
		BuildBox current = box;
		while (true) {
			BuildBox outside = nonAirInShell(current, level);
			if (outside == null) {
				return new BoxExpansion(box, current, true);
			}
			BuildBox grown = current.union(outside);
			if (grown.volume() > MAX_VOLUME) {
				return new BoxExpansion(box, current, false);
			}
			current = grown;
		}
	}

	/**
	 * Whether nothing but air surrounds {@code box} in {@code level}, corners and edges included; when this is false,
	 * {@link #of} would grow the box. Reads only the one-block shell around the box.
	 */
	public static boolean isEnclosed(BuildBox box, Level level) {
		return nonAirInShell(box, level) == null;
	}

	/** Whether the box grew at all. */
	public boolean grew() {
		return !from.equals(to);
	}

	/**
	 * The bounding box of every block in the one-block shell around {@code box} that is not air, or null if the shell
	 * is all air. Only the shell is read, never the inside of the box.
	 */
	private static @Nullable BuildBox nonAirInShell(BuildBox box, Level level) {
		BuildBox shell = box.grow(1);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos min = new BlockPos.MutableBlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
		BlockPos.MutableBlockPos max = new BlockPos.MutableBlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
		boolean found = false;
		for (int x = shell.min().getX(); x <= shell.max().getX(); x++) {
			for (int y = shell.min().getY(); y <= shell.max().getY(); y++) {
				// A column on one of the four x or y faces is shell all the way along z; every other column only at its two ends.
				boolean face = x == shell.min().getX() || x == shell.max().getX() || y == shell.min().getY() || y == shell.max().getY();
				int step = face ? 1 : shell.max().getZ() - shell.min().getZ();
				for (int z = shell.min().getZ(); z <= shell.max().getZ(); z += step) {
					if (level.getBlockState(pos.set(x, y, z)).isAir()) {
						continue;
					}
					found = true;
					min.set(Math.min(min.getX(), x), Math.min(min.getY(), y), Math.min(min.getZ(), z));
					max.set(Math.max(max.getX(), x), Math.max(max.getY(), y), Math.max(max.getZ(), z));
				}
			}
		}
		return found ? new BuildBox(min.immutable(), max.immutable()) : null;
	}
}
