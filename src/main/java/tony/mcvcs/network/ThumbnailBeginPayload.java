package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;

/**
 * Server to client: the blocks of a version asked for by a {@link ThumbnailRequestPayload} follow, as
 * {@link ThumbnailBlocksPayload}s filling {@code extent} in {@link BuildBox} order.
 *
 * @param build   the build's name
 * @param version the version the blocks are of
 * @param extent  the version's extent in build space, which the blocks fill
 */
public record ThumbnailBeginPayload(String build, int version, BuildBox extent) implements CustomPacketPayload {
	public static final Type<ThumbnailBeginPayload> TYPE = new Type<>(MCVCS.id("thumbnail_begin"));
	public static final StreamCodec<ByteBuf, ThumbnailBeginPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ThumbnailBeginPayload::build,
		ByteBufCodecs.VAR_INT, ThumbnailBeginPayload::version,
		BuildBox.STREAM_CODEC, ThumbnailBeginPayload::extent,
		ThumbnailBeginPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
