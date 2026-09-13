package tony.mcvcs.build;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import io.netty.buffer.ByteBuf;

/**
 * What the client knows about a build: enough to draw its bounding box and label it, nothing that needs WorldEdit.
 *
 * @param name      build name
 * @param version   latest saved version
 * @param dimension the world the box is in
 * @param box       the region the build covers
 */
public record ClientBuild(String name, int version, ResourceKey<Level> dimension, BuildBox box) {
	public static final StreamCodec<ByteBuf, ClientBuild> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ClientBuild::name,
		ByteBufCodecs.VAR_INT, ClientBuild::version,
		ResourceKey.streamCodec(Registries.DIMENSION), ClientBuild::dimension,
		BuildBox.STREAM_CODEC, ClientBuild::box,
		ClientBuild::new
	);

	/** The client-facing view of {@code build}. */
	public static ClientBuild of(Build build) {
		return new ClientBuild(build.name(), build.version(), build.dimension(), build.box());
	}
}
