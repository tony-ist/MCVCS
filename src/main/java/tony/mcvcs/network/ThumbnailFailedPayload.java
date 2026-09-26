package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Server to client: the blocks a {@link ThumbnailRequestPayload} asked for cannot be had, and why, so the overlay
 * stops waiting for them.
 *
 * @param build   the build's name
 * @param version the version that was asked for
 * @param reason  what went wrong, short enough to show in the build's cell
 */
public record ThumbnailFailedPayload(String build, int version, String reason) implements CustomPacketPayload {
	public static final Type<ThumbnailFailedPayload> TYPE = new Type<>(MCVCS.id("thumbnail_failed"));
	public static final StreamCodec<ByteBuf, ThumbnailFailedPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ThumbnailFailedPayload::build,
		ByteBufCodecs.VAR_INT, ThumbnailFailedPayload::version,
		ByteBufCodecs.STRING_UTF8, ThumbnailFailedPayload::reason,
		ThumbnailFailedPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
