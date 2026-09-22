package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/** Server to client: stop drawing the copy {@code /vcs place} was about to put down, confirmed or cancelled. */
public record PlacePreviewClearPayload() implements CustomPacketPayload {
	public static final PlacePreviewClearPayload INSTANCE = new PlacePreviewClearPayload();
	public static final Type<PlacePreviewClearPayload> TYPE = new Type<>(MCVCS.id("place_preview_clear"));
	public static final StreamCodec<ByteBuf, PlacePreviewClearPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
