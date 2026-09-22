package tony.mcvcs.network;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.ClientPlacement;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: every placement of every build in the world the player is playing and the label of the one they
 * have selected, if any. Replaces whatever the client knew before.
 *
 * @param placements every placement in the world, sorted by build then placement name
 * @param selected   the {@link ClientPlacement#label label} of one of {@code placements}, or empty when nothing is
 *                   selected
 */
public record BuildsPayload(List<ClientPlacement> placements, Optional<String> selected) implements CustomPacketPayload {
	public static final Type<BuildsPayload> TYPE = new Type<>(MCVCS.id("builds"));
	public static final StreamCodec<ByteBuf, BuildsPayload> STREAM_CODEC = StreamCodec.composite(
		ClientPlacement.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildsPayload::placements,
		ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), BuildsPayload::selected,
		BuildsPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
