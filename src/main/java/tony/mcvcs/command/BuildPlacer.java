package tony.mcvcs.command;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.Schematic;

/**
 * Puts blocks into the world for the commands that change it: {@code /vcs load} and {@code /vcs place} lay a
 * version over the boxes they empty, and {@code /vcs unplace} and {@code /vcs delete -c} only empty a box.
 * <p>
 * The blocks are set with {@link #FLAGS}: clients are told, and nothing else happens. No block is told its
 * neighbour changed, none reshapes itself to its neighbours, none reacts to being placed, and a container that is
 * replaced does not spill what it held. So redstone components, observers and falling blocks are left exactly as they
 * were saved instead of reacting to their neighbours appearing one by one. Light is worked out as for any other
 * change of block.
 */
final class BuildPlacer {
	/** How every block is set: sent to clients, with every side effect the game lets a caller skip skipped. */
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private BuildPlacer() {
	}

	/**
	 * Empties every box in {@code clear} and puts {@code schematic} into {@code covered}, which must be the world
	 * box the version belongs in at its placement and the size of the schematic's own box. A schematic carries the
	 * coordinates of the placement it was committed from, which mean nothing at another one, so only its shape is
	 * used and {@code covered} says where it goes.
	 *
	 * @throws IllegalArgumentException if {@code covered} is not the size of the schematic
	 */
	static void place(ServerLevel level, List<BuildBox> clear, Schematic schematic, BuildBox covered) {
		BoxSnapshot blocks = BoxSnapshot.ofSchematic(covered, schematic, covered);
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(MCVCS.LOGGER)) {
			// The version goes over whatever part of the boxes it covers, so each block is set only once.
			for (BuildBox box : clear) {
				for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
					if (!covered.contains(pos)) {
						set(level, pos, Blocks.AIR.defaultBlockState(), null, reporter);
					}
				}
			}
			for (BlockPos pos : BlockPos.betweenClosed(covered.min(), covered.max())) {
				int index = covered.index(pos.getX(), pos.getY(), pos.getZ());
				set(level, pos, blocks.state(index), blocks.data(index), reporter);
			}
		}
	}

	/** Empties {@code box}. */
	static void clear(ServerLevel level, BuildBox box) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(MCVCS.LOGGER)) {
			for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
				set(level, pos, Blocks.AIR.defaultBlockState(), null, reporter);
			}
		}
	}

	/**
	 * Makes the block at {@code pos} {@code state}, with a block entity holding {@code data} if it has one. A block
	 * that is already exactly that is left alone.
	 */
	private static void set(ServerLevel level, BlockPos pos, BlockState state, @Nullable CompoundTag data, ProblemReporter reporter) {
		BlockState current = level.getBlockState(pos);
		if (current.hasBlockEntity() && current.is(state.getBlock())) {
			// The game keeps a block entity while its block stays, so whatever it holds now would survive. Taking the
			// block away for a moment, without telling clients, makes the new block start with a fresh one.
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
		} else if (current == state) {
			return;
		}
		level.setBlock(pos, state, FLAGS);
		if (data == null) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity != null) {
			blockEntity.loadWithComponents(TagValueInput.create(reporter.forChild(blockEntity.problemPath()), level.registryAccess(), data));
		}
	}
}
