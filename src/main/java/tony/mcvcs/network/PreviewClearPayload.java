package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/** Server to client: stop showing the preview and go back to the real blocks. */
public record PreviewClearPayload() implements CustomPacketPayload {
	public static final PreviewClearPayload INSTANCE = new PreviewClearPayload();
	public static final Type<PreviewClearPayload> TYPE = new Type<>(MCVCS.id("preview_clear"));
	public static final StreamCodec<ByteBuf, PreviewClearPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
