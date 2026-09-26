package tony.mcvcs.network;

import java.util.List;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Server to client, in answer to a {@link BuildListRequestPayload}: every build in the world being played, sorted by
 * name, or nothing at all if the player may not run {@code /vcs}.
 *
 * @param allowed whether the player has the permission {@code /vcs} needs; {@code builds} is empty when not
 * @param builds  every build in the world, sorted by name
 */
public record BuildListPayload(boolean allowed, List<BuildSummary> builds) implements CustomPacketPayload {
	public static final Type<BuildListPayload> TYPE = new Type<>(MCVCS.id("build_list"));
	public static final StreamCodec<ByteBuf, BuildListPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, BuildListPayload::allowed,
		BuildSummary.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildListPayload::builds,
		BuildListPayload::new
	);

	public BuildListPayload {
		builds = List.copyOf(builds);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
