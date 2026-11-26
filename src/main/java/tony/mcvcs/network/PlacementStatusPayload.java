package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Server to client, in answer to a {@link PlacementStatusRequestPayload}: how many blocks of the selected placement
 * differ from the version it holds.
 *
 * @param placement the selected placement's label, e.g. {@code tower/main}; empty if none is selected
 * @param modified  how many blocks differ, or {@link #UNKNOWN} if that could not be worked out
 */
public record PlacementStatusPayload(String placement, int modified) implements CustomPacketPayload {
	public static final int UNKNOWN = -1;
	public static final Type<PlacementStatusPayload> TYPE = new Type<>(MCVCS.id("placement_status"));
	public static final StreamCodec<ByteBuf, PlacementStatusPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, PlacementStatusPayload::placement,
		ByteBufCodecs.VAR_INT, PlacementStatusPayload::modified,
		PlacementStatusPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
