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
 * client can outline the box the click would make, and the build the click would create if a {@code /vcs create} is
 * waiting for it, so the builds overlay can offer to cancel that.
 */
public record PendingClickPayload(boolean armed, Optional<BlockPos> corner, Optional<String> creating) implements CustomPacketPayload {
	public static final Type<PendingClickPayload> TYPE = new Type<>(MCVCS.id("pending_click"));
	public static final StreamCodec<ByteBuf, PendingClickPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, PendingClickPayload::armed,
		ByteBufCodecs.optional(BlockPos.STREAM_CODEC), PendingClickPayload::corner,
		ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), PendingClickPayload::creating,
		PendingClickPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
