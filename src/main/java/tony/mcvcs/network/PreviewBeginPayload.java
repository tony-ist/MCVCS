package tony.mcvcs.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import tony.mcvcs.MCVCS;
import tony.mcvcs.project.ProjectBox;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: a new preview is about to be streamed. The client drops any preview it was still receiving and
 * waits for {@link PreviewBlocksPayload}s covering {@code box}.
 *
 * @param name      build name
 * @param version   build version being previewed
 * @param dimension the world the box is in
 * @param box       the region the build covers
 */
public record PreviewBeginPayload(String name, int version, ResourceKey<Level> dimension, ProjectBox box) implements CustomPacketPayload {
	public static final Type<PreviewBeginPayload> TYPE = new Type<>(MCVCS.id("preview_begin"));
	public static final StreamCodec<ByteBuf, PreviewBeginPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, PreviewBeginPayload::name,
		ByteBufCodecs.VAR_INT, PreviewBeginPayload::version,
		ResourceKey.streamCodec(Registries.DIMENSION), PreviewBeginPayload::dimension,
		ProjectBox.STREAM_CODEC, PreviewBeginPayload::box,
		PreviewBeginPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
