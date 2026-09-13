package tony.mcvcs.project;

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
public record ClientProject(String name, int version, ResourceKey<Level> dimension, ProjectBox box) {
	public static final StreamCodec<ByteBuf, ClientProject> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ClientProject::name,
		ByteBufCodecs.VAR_INT, ClientProject::version,
		ResourceKey.streamCodec(Registries.DIMENSION), ClientProject::dimension,
		ProjectBox.STREAM_CODEC, ClientProject::box,
		ClientProject::new
	);

	/** The client-facing view of {@code project}. */
	public static ClientProject of(Project project) {
		return new ClientProject(project.name(), project.version(), project.dimension(), project.box());
	}
}
