package tony.mcvcs.network;

import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: one slice of the preview announced by the last {@link PreviewBeginPayload}.
 * <p>
 * Blocks are palette-compressed: {@code indices[i]} is the palette entry of the block at
 * {@link BuildBox} index {@code offset + i}. Slices arrive in order and the one flagged {@code last} completes the
 * preview.
 *
 * @param offset  box index of the first block in this slice
 * @param palette distinct block states used by this slice
 * @param indices palette index per block, in box order
 * @param last    whether this is the final slice of the preview
 */
public record PreviewBlocksPayload(int offset, List<BlockState> palette, int[] indices, boolean last) implements CustomPacketPayload {
	public static final Type<PreviewBlocksPayload> TYPE = new Type<>(MCVCS.id("preview_blocks"));
	public static final StreamCodec<FriendlyByteBuf, PreviewBlocksPayload> STREAM_CODEC = CustomPacketPayload.codec(PreviewBlocksPayload::write, PreviewBlocksPayload::read);

	/** Block state ids match between client and server because the block registry is synced on join. */
	private static final StreamCodec<ByteBuf, BlockState> BLOCK_STATE = ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY);

	private static PreviewBlocksPayload read(FriendlyByteBuf buf) {
		int offset = buf.readVarInt();
		List<BlockState> palette = buf.readList(BLOCK_STATE);
		int[] indices = buf.readVarIntArray();
		boolean last = buf.readBoolean();
		return new PreviewBlocksPayload(offset, palette, indices, last);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(offset);
		buf.writeCollection(palette, BLOCK_STATE);
		buf.writeVarIntArray(indices);
		buf.writeBoolean(last);
	}

	/** Block state at slice position {@code i}. */
	public BlockState state(int i) {
		return palette.get(indices[i]);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
