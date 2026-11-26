package tony.mcvcs.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: the player dragged a face of the box {@code /vcs resize} is showing, which is now {@code box}.
 * The whole box is sent rather than a step, so a lost packet is made good by the next one. The server only checks it
 * when the resize is confirmed.
 */
public record ResizeMovePayload(BuildBox box) implements CustomPacketPayload {
	public static final Type<ResizeMovePayload> TYPE = new Type<>(MCVCS.id("resize_move"));
	public static final StreamCodec<ByteBuf, ResizeMovePayload> STREAM_CODEC = StreamCodec.composite(
		BuildBox.STREAM_CODEC, ResizeMovePayload::box,
		ResizeMovePayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
