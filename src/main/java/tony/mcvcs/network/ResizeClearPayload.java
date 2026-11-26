package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/** Server to client: the resize {@link ResizeBeginPayload} started was confirmed or cancelled. */
public record ResizeClearPayload() implements CustomPacketPayload {
	public static final ResizeClearPayload INSTANCE = new ResizeClearPayload();
	public static final Type<ResizeClearPayload> TYPE = new Type<>(MCVCS.id("resize_clear"));
	public static final StreamCodec<ByteBuf, ResizeClearPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
