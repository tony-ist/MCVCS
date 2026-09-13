package tony.mcvcs.diff;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block that differs between the two sides of a {@link BuildDiff}.
 *
 * @param pos  world position of the block
 * @param from the block on the old side
 * @param to   the block on the new side, never the same state as {@code from}
 */
public record BlockChange(BlockPos pos, BlockState from, BlockState to) {
	public BlockChange {
		if (from == to) {
			throw new IllegalArgumentException("Block at " + pos + " is " + from + " on both sides");
		}
	}

	public ChangeKind kind() {
		return ChangeKind.between(from, to);
	}
}
