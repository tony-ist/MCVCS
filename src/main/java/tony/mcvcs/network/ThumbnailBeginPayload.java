package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.PreviewGrid;

/**
 * Server to client: the blocks of a version asked for by a {@link ThumbnailRequestPayload} follow, as
 * {@link ThumbnailBlocksPayload}s filling the version's {@link PreviewGrid#grid preview grid} at {@code scale} in
 * {@link BuildBox} order: the version itself at scale 1, a coarser grid of it when it is big.
 *
 * @param build   the build's name
 * @param version the version the blocks are of
 * @param extent  the version's extent in build space
 * @param scale   how many of the version's blocks each block sent stands for along each side, see {@link PreviewGrid}
 */
public record ThumbnailBeginPayload(String build, int version, BuildBox extent, int scale) implements CustomPacketPayload {
	public static final Type<ThumbnailBeginPayload> TYPE = new Type<>(MCVCS.id("thumbnail_begin"));
	public static final StreamCodec<ByteBuf, ThumbnailBeginPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ThumbnailBeginPayload::build,
		ByteBufCodecs.VAR_INT, ThumbnailBeginPayload::version,
		BuildBox.STREAM_CODEC, ThumbnailBeginPayload::extent,
		ByteBufCodecs.VAR_INT, ThumbnailBeginPayload::scale,
		ThumbnailBeginPayload::new
	);

	/** The grid the blocks fill. */
	public BuildBox grid() {
		return PreviewGrid.grid(extent, scale);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
