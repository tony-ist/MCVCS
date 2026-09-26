package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Client to server: the builds overlay wants the blocks of one version of a build to draw its preview with. The
 * server answers with a {@link ThumbnailBeginPayload} and {@link ThumbnailBlocksPayload}s, or a
 * {@link ThumbnailFailedPayload}.
 *
 * @param build   the build's name
 * @param version the version whose blocks are wanted
 */
public record ThumbnailRequestPayload(String build, int version) implements CustomPacketPayload {
	public static final Type<ThumbnailRequestPayload> TYPE = new Type<>(MCVCS.id("thumbnail_request"));
	public static final StreamCodec<ByteBuf, ThumbnailRequestPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ThumbnailRequestPayload::build,
		ByteBufCodecs.VAR_INT, ThumbnailRequestPayload::version,
		ThumbnailRequestPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
