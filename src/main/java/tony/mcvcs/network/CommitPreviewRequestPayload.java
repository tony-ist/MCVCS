package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Client to server: the builds overlay's commit window wants to see what committing the selected placement would
 * save, answered with a {@link CommitPreviewPayload}.
 */
public record CommitPreviewRequestPayload() implements CustomPacketPayload {
	public static final CommitPreviewRequestPayload INSTANCE = new CommitPreviewRequestPayload();
	public static final Type<CommitPreviewRequestPayload> TYPE = new Type<>(MCVCS.id("commit_preview_request"));
	public static final StreamCodec<ByteBuf, CommitPreviewRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
