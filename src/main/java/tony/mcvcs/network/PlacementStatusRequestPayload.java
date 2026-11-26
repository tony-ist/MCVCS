package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Client to server: the builds overlay's checkout window wants to know how many blocks of the selected placement
 * differ from the version it holds, see {@link PlacementStatusPayload}.
 */
public record PlacementStatusRequestPayload() implements CustomPacketPayload {
	public static final PlacementStatusRequestPayload INSTANCE = new PlacementStatusRequestPayload();
	public static final Type<PlacementStatusRequestPayload> TYPE = new Type<>(MCVCS.id("placement_status_request"));
	public static final StreamCodec<ByteBuf, PlacementStatusRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
