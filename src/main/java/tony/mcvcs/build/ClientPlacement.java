package tony.mcvcs.build;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import io.netty.buffer.ByteBuf;

/**
 * What the client knows about one placement of a build: enough to draw its bounding box and label it, nothing that
 * needs WorldEdit. The client is sent one of these per placement, not per build, since that is what it draws.
 *
 * @param build     the name of the build the placement is of
 * @param placement the placement's name within the build
 * @param head      the version the placement holds
 * @param dimension the dimension the placement stands in
 * @param box       the box it covers there
 */
public record ClientPlacement(String build, String placement, int head, ResourceKey<Level> dimension, BuildBox box) {
	public static final StreamCodec<ByteBuf, ClientPlacement> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ClientPlacement::build,
		ByteBufCodecs.STRING_UTF8, ClientPlacement::placement,
		ByteBufCodecs.VAR_INT, ClientPlacement::head,
		ResourceKey.streamCodec(Registries.DIMENSION), ClientPlacement::dimension,
		BuildBox.STREAM_CODEC, ClientPlacement::box,
		ClientPlacement::new
	);

	/** The client-facing view of {@code placement}. */
	public static ClientPlacement of(BuildPlacement placement) {
		return new ClientPlacement(placement.build().name(), placement.name(), placement.head(), placement.dimension(), placement.box());
	}

	/** How the placement is written in chat and in its label above the world, e.g. {@code tower/testrig}. */
	public String label() {
		return build + Build.LABEL_SEPARATOR + placement;
	}
}
