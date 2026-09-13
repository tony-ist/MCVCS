package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/** Server to client: stop highlighting the diff. */
public record DiffClearPayload() implements CustomPacketPayload {
	public static final DiffClearPayload INSTANCE = new DiffClearPayload();
	public static final Type<DiffClearPayload> TYPE = new Type<>(MCVCS.id("diff_clear"));
	public static final StreamCodec<ByteBuf, DiffClearPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
