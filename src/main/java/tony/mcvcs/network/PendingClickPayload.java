package tony.mcvcs.network;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: whether a command is waiting for the player to click a block, so the client can highlight the
 * block their click would hand over, and the corner already picked if the click is the second corner of a box, so the
 * client can outline the box the click would make.
 */
public record PendingClickPayload(boolean armed, Optional<BlockPos> corner) implements CustomPacketPayload {
	public static final Type<PendingClickPayload> TYPE = new Type<>(MCVCS.id("pending_click"));
	public static final StreamCodec<ByteBuf, PendingClickPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, PendingClickPayload::armed,
		ByteBufCodecs.optional(BlockPos.STREAM_CODEC), PendingClickPayload::corner,
		PendingClickPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
