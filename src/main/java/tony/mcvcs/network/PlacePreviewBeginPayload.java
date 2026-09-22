package tony.mcvcs.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: a {@code /vcs place} that has not happened yet is about to be streamed, so the client can draw
 * the copy where it would land and let the player move it there. The blocks arrive as {@link PreviewBlocksPayload}s
 * covering {@code box}, exactly as they do after a {@link PreviewBeginPayload}.
 * <p>
 * Unlike a version preview, this one is the client's to move: every hotkey press slides it a block and tells the
 * server where it now stands with a {@link PlacePreviewMovePayload}, and nothing is put into the world until
 * {@code /vcs confirmPlace}.
 *
 * @param label     how the placement would be named, e.g. {@code tower/p2}
 * @param version   the version that would be placed
 * @param dimension the world the copy would stand in
 * @param box       where it stands right now, before the player moves it
 * @param force     whether the command was given {@code -f}, so blocks in the way do not stop the placement
 */
public record PlacePreviewBeginPayload(String label, int version, ResourceKey<Level> dimension, BuildBox box, boolean force) implements CustomPacketPayload {
	public static final Type<PlacePreviewBeginPayload> TYPE = new Type<>(MCVCS.id("place_preview_begin"));
	public static final StreamCodec<ByteBuf, PlacePreviewBeginPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, PlacePreviewBeginPayload::label,
		ByteBufCodecs.VAR_INT, PlacePreviewBeginPayload::version,
		ResourceKey.streamCodec(Registries.DIMENSION), PlacePreviewBeginPayload::dimension,
		BuildBox.STREAM_CODEC, PlacePreviewBeginPayload::box,
		ByteBufCodecs.BOOL, PlacePreviewBeginPayload::force,
		PlacePreviewBeginPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
