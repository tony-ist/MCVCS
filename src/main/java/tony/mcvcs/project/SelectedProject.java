package tony.mcvcs.project;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import io.netty.buffer.ByteBuf;

/**
 * What the client knows about the player's selected project: enough to draw its bounding box and label it, nothing
 * that needs WorldEdit.
 *
 * @param name      build name
 * @param version   latest saved version
 * @param dimension the world the box is in
 * @param box       the region the build covers
 */
public record SelectedProject(String name, int version, ResourceKey<Level> dimension, ProjectBox box) {
	public static final StreamCodec<ByteBuf, SelectedProject> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, SelectedProject::name,
		ByteBufCodecs.VAR_INT, SelectedProject::version,
		ResourceKey.streamCodec(Registries.DIMENSION), SelectedProject::dimension,
		ProjectBox.STREAM_CODEC, SelectedProject::box,
		SelectedProject::new
	);

	/** The client-facing view of {@code project}. */
	public static SelectedProject of(Project project) {
		return new SelectedProject(project.name(), project.version(), project.dimension(), project.box());
	}
}
