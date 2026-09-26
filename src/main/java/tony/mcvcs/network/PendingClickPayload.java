package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: whether a command is waiting for the player to click a block, so the client can highlight the
 * block their click would hand over.
 */
public record PendingClickPayload(boolean armed) implements CustomPacketPayload {
	public static final Type<PendingClickPayload> TYPE = new Type<>(MCVCS.id("pending_click"));
	public static final StreamCodec<ByteBuf, PendingClickPayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(PendingClickPayload::new, PendingClickPayload::armed);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
