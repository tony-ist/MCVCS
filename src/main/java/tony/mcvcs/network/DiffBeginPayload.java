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
 * Server to client: a new diff is about to be streamed. The client drops any diff it was still receiving and waits
 * for {@link DiffChangesPayload}s until it has {@code total} changes; a diff with no changes is complete at once.
 *
 * @param name      build name
 * @param version   the version the world was compared against
 * @param dimension the world the box is in
 * @param box       the region the build covers
 * @param total     how many changes the diff has
 */
public record DiffBeginPayload(String name, int version, ResourceKey<Level> dimension, BuildBox box, int total) implements CustomPacketPayload {
	public static final Type<DiffBeginPayload> TYPE = new Type<>(MCVCS.id("diff_begin"));
	public static final StreamCodec<ByteBuf, DiffBeginPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, DiffBeginPayload::name,
		ByteBufCodecs.VAR_INT, DiffBeginPayload::version,
		ResourceKey.streamCodec(Registries.DIMENSION), DiffBeginPayload::dimension,
		BuildBox.STREAM_CODEC, DiffBeginPayload::box,
		ByteBufCodecs.VAR_INT, DiffBeginPayload::total,
		DiffBeginPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
