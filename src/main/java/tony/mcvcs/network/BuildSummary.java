package tony.mcvcs.network;

import java.util.List;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;

/**
 * What the builds overlay shows of one build before its preview has arrived: its name, its newest committed version
 * with that version's tags, and that version's extent in build space, whose volume decides whether the preview is
 * downloaded straight away. Of its placements only their number is sent, which decides whether the overlay offers to
 * select the build and teleport to it: the preview is about the build's versions, not where they stand.
 *
 * @param name       the build's name
 * @param version    the build's newest committed version
 * @param tags       the tags of {@code version}, sorted; empty if it has none
 * @param extent     the extent of {@code version} in build space
 * @param placements how many placements the build has
 */
public record BuildSummary(String name, int version, List<String> tags, BuildBox extent, int placements) {
	public static final StreamCodec<ByteBuf, BuildSummary> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, BuildSummary::name,
		ByteBufCodecs.VAR_INT, BuildSummary::version,
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), BuildSummary::tags,
		BuildBox.STREAM_CODEC, BuildSummary::extent,
		ByteBufCodecs.VAR_INT, BuildSummary::placements,
		BuildSummary::new
	);

	public BuildSummary {
		tags = List.copyOf(tags);
	}

	/** The summary of {@code build} at its newest version. */
	public static BuildSummary of(Build build) {
		return new BuildSummary(build.name(), build.version(), build.tagsOf(build.version()), build.extent(build.version()), build.placements().size());
	}

	/** The version as chat writes it, e.g. {@code v2 (2.0.0, stable)}; the same as {@link Build#versionLabel}. */
	public String versionLabel() {
		return "v" + version + (tags.isEmpty() ? "" : " (" + String.join(", ", tags) + ")");
	}
}
