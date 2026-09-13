package tony.mcvcs.network;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.ClientBuild;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: every build in the world the player is playing and the name of the one they have selected, if
 * any. Replaces whatever the client knew before.
 *
 * @param builds every build in the world, sorted by name
 * @param selected the name of one of {@code builds}, or empty when nothing is selected
 */
public record BuildsPayload(List<ClientBuild> builds, Optional<String> selected) implements CustomPacketPayload {
	public static final Type<BuildsPayload> TYPE = new Type<>(MCVCS.id("builds"));
	public static final StreamCodec<ByteBuf, BuildsPayload> STREAM_CODEC = StreamCodec.composite(
		ClientBuild.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildsPayload::builds,
		ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), BuildsPayload::selected,
		BuildsPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
