package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/** Client to server: the builds overlay was opened or refreshed and wants every build in the world, see {@link BuildListPayload}. */
public record BuildListRequestPayload() implements CustomPacketPayload {
	public static final BuildListRequestPayload INSTANCE = new BuildListRequestPayload();
	public static final Type<BuildListRequestPayload> TYPE = new Type<>(MCVCS.id("build_list_request"));
	public static final StreamCodec<ByteBuf, BuildListRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
