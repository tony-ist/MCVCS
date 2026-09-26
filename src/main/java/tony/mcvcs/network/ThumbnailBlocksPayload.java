package tony.mcvcs.network;

import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.MCVCS;

/**
 * Server to client: one slice of the blocks a {@link ThumbnailBeginPayload} announced, palette-compressed the same way
 * as {@link PreviewBlocksPayload}. Each slice names its build and version, so slices are never taken for another
 * preview's.
 *
 * @param build   the build's name
 * @param version the version the blocks are of
 * @param offset  box index of the first block in this slice
 * @param palette distinct block states used by this slice
 * @param indices palette index per block, in box order
 * @param last    whether this is the final slice
 */
public record ThumbnailBlocksPayload(String build, int version, int offset, List<BlockState> palette, int[] indices, boolean last) implements CustomPacketPayload {
	public static final Type<ThumbnailBlocksPayload> TYPE = new Type<>(MCVCS.id("thumbnail_blocks"));
	public static final StreamCodec<FriendlyByteBuf, ThumbnailBlocksPayload> STREAM_CODEC = CustomPacketPayload.codec(ThumbnailBlocksPayload::write, ThumbnailBlocksPayload::read);

	private static ThumbnailBlocksPayload read(FriendlyByteBuf buf) {
		String build = buf.readUtf();
		int version = buf.readVarInt();
		int offset = buf.readVarInt();
		List<BlockState> palette = buf.readList(BlockPalette.STATE_CODEC);
		int[] indices = buf.readVarIntArray();
		boolean last = buf.readBoolean();
		return new ThumbnailBlocksPayload(build, version, offset, palette, indices, last);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(build);
		buf.writeVarInt(version);
		buf.writeVarInt(offset);
		buf.writeCollection(palette, BlockPalette.STATE_CODEC);
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
