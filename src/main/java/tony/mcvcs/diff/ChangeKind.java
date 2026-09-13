package tony.mcvcs.diff;

import net.minecraft.world.level.block.state.BlockState;

/** How a block differs between the old and the new side of a {@link BuildDiff}. */
public enum ChangeKind {
	/** Air before, a block now. */
	ADDED,
	/** A block before, air now. */
	REMOVED,
	/** A block before and a different one now. A different state of the same block, e.g. a rotated repeater, counts. */
	CHANGED;

	/** The kind of change from {@code from} to {@code to}, which must differ. Cave and void air count as air. */
	public static ChangeKind between(BlockState from, BlockState to) {
		if (from.isAir()) {
			return ADDED;
		}
		return to.isAir() ? REMOVED : CHANGED;
	}
}
