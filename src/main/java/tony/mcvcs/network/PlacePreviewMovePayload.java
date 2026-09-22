package tony.mcvcs.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: the player moved the copy {@code /vcs place} is about to put down, and it now starts at
 * {@code min}. The client has already drawn it there; the server only remembers where {@code /vcs confirmPlace}
 * will place it.
 * <p>
 * The position is absolute rather than a step, so a packet that never arrives is made good by the next one instead
 * of leaving the two sides a block apart for good. The box keeps the size the placement was announced with; only its
 * minimum corner moves.
 */
public record PlacePreviewMovePayload(BlockPos min) implements CustomPacketPayload {
	public static final Type<PlacePreviewMovePayload> TYPE = new Type<>(MCVCS.id("place_preview_move"));
	public static final StreamCodec<ByteBuf, PlacePreviewMovePayload> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, PlacePreviewMovePayload::min,
		PlacePreviewMovePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
