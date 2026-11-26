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
 * Server to client: {@code /vcs resize} started on the placement {@code label}, whose box is {@code box} in
 * {@code dimension}. The client lets the player drag the box's faces from there and reports each change with a
 * {@link ResizeMovePayload}; {@link ResizeClearPayload} ends it.
 */
public record ResizeBeginPayload(String label, ResourceKey<Level> dimension, BuildBox box) implements CustomPacketPayload {
	public static final Type<ResizeBeginPayload> TYPE = new Type<>(MCVCS.id("resize_begin"));
	public static final StreamCodec<ByteBuf, ResizeBeginPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ResizeBeginPayload::label,
		ResourceKey.streamCodec(Registries.DIMENSION), ResizeBeginPayload::dimension,
		BuildBox.STREAM_CODEC, ResizeBeginPayload::box,
		ResizeBeginPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
