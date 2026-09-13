package tony.mcvcs.diff;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block that differs between the two sides of a {@link BuildDiff}: by its state, by the data of its block entity
 * (the contents of a container, the text of a sign), or both.
 *
 * @param pos         world position of the block
 * @param from        the block on the old side
 * @param to          the block on the new side
 * @param dataChanged whether the block entity data differs between the sides; must be true when {@code from} and
 *                    {@code to} are the same state, or there would be no change
 */
public record BlockChange(BlockPos pos, BlockState from, BlockState to, boolean dataChanged) {
	public BlockChange {
		if (from == to && !dataChanged) {
			throw new IllegalArgumentException("Block at " + pos + " is " + from + " on both sides");
		}
	}

	/** A change of state only. */
	public BlockChange(BlockPos pos, BlockState from, BlockState to) {
		this(pos, from, to, false);
	}

	/** Whether the block is the same state on both sides and only its block entity data differs. */
	public boolean isDataOnly() {
		return from == to;
	}

	/** How the block differs; a change of block entity data alone is a {@link ChangeKind#CHANGED} block. */
	public ChangeKind kind() {
		return isDataOnly() ? ChangeKind.CHANGED : ChangeKind.between(from, to);
	}
}
