package tony.mcvcs.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * The distinct block states used by one slice of a block payload, each numbered by first appearance, so the slice
 * can carry a small index per block instead of a whole state. Built on the server while a slice is assembled; the
 * client gets the finished {@link #states() list} and looks indices up in it.
 */
public final class BlockPalette {
	/** Block state ids match between client and server because the block registry is synced on join. */
	public static final StreamCodec<ByteBuf, BlockState> STATE_CODEC = ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY);

	private final List<BlockState> states = new ArrayList<>();
	private final Object2IntMap<BlockState> indices = new Object2IntOpenHashMap<>();

	/** The index of {@code state}, adding it to the palette if it is not in it yet. */
	public int indexOf(BlockState state) {
		int index = indices.getOrDefault(state, -1);
		if (index < 0) {
			index = states.size();
			states.add(state);
			indices.put(state, index);
		}
		return index;
	}

	/** Every state in the palette, in index order. */
	public List<BlockState> states() {
		return List.copyOf(states);
	}
}
