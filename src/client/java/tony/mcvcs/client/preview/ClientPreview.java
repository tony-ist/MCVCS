package tony.mcvcs.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.project.ProjectBox;

/**
 * A fully received preview: the build version's blocks, indexed in {@link ProjectBox} order.
 * <p>
 * Immutable, so the section compile threads can read it without locking. {@code blocks} must not be modified after
 * construction.
 */
public record ClientPreview(String name, int version, ResourceKey<Level> dimension, ProjectBox box, BlockState[] blocks) {
	/** Whether {@code pos} in the dimension {@code dimension} is drawn from the preview instead of the real world. */
	public boolean covers(ResourceKey<Level> dimension, BlockPos pos) {
		return box.contains(pos) && this.dimension.equals(dimension);
	}

	/** Block to draw at {@code pos}, which must be {@linkplain #covers covered}. */
	public BlockState stateAt(BlockPos pos) {
		return blocks[box.index(pos.getX(), pos.getY(), pos.getZ())];
	}
}
