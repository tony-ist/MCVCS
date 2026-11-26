package tony.mcvcs.network;

import java.util.List;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;

/**
 * What the builds overlay shows of one build before its previews have arrived: its name and every committed version
 * with that version's tags and extent in build space, whose volume decides whether its preview is downloaded straight
 * away. Of its placements only their number is sent, which decides whether the overlay offers to select the build and
 * teleport to it: the preview is about the build's versions, not where they stand.
 *
 * @param name       the build's name
 * @param versions   every version from 1 up, oldest first; never empty
 * @param placements how many placements the build has
 */
public record BuildSummary(String name, List<Version> versions, int placements) {
	public static final StreamCodec<ByteBuf, BuildSummary> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, BuildSummary::name,
		Version.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildSummary::versions,
		ByteBufCodecs.VAR_INT, BuildSummary::placements,
		BuildSummary::new
	);

	public BuildSummary {
		versions = List.copyOf(versions);
		if (versions.isEmpty()) {
			throw new IllegalArgumentException("Build '" + name + "' has no versions");
		}
	}

	/** The summary of {@code build} with all its versions. */
	public static BuildSummary of(Build build) {
		List<Version> versions = build.versionNumbers().stream()
			.map(version -> new Version(version, build.tagsOf(version), build.extent(version)))
			.toList();
		return new BuildSummary(build.name(), versions, build.placements().size());
	}

	/** The newest committed version. */
	public Version latest() {
		return versions.getLast();
	}

	/** The newest committed version's number. */
	public int version() {
		return latest().version();
	}

	/** The tags of {@link #version()}, sorted; empty if it has none. */
	public List<String> tags() {
		return latest().tags();
	}

	/** The extent of {@link #version()} in build space. */
	public BuildBox extent() {
		return latest().extent();
	}

	/** The newest version as chat writes it, e.g. {@code v2 (2.0.0, stable)}; the same as {@link Build#versionLabel}. */
	public String versionLabel() {
		return latest().label();
	}

	/**
	 * One committed version of a build.
	 *
	 * @param version the version number
	 * @param tags    its tags, sorted; empty if it has none
	 * @param extent  its extent in build space
	 */
	public record Version(int version, List<String> tags, BuildBox extent) {
		public static final StreamCodec<ByteBuf, Version> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Version::version,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Version::tags,
			BuildBox.STREAM_CODEC, Version::extent,
			Version::new
		);

		public Version {
			tags = List.copyOf(tags);
		}

		/** As chat writes it, e.g. {@code v2 (2.0.0, stable)}. */
		public String label() {
			return "v" + version + (tags.isEmpty() ? "" : " (" + String.join(", ", tags) + ")");
		}
	}
}
