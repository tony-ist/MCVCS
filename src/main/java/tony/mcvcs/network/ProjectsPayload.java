package tony.mcvcs.network;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import tony.mcvcs.project.ClientProject;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: every build in the world the player is playing and the name of the one they have selected, if
 * any. Replaces whatever the client knew before.
 *
 * @param projects every build in the world, sorted by name
 * @param selected the name of one of {@code projects}, or empty when nothing is selected
 */
public record ProjectsPayload(List<ClientProject> projects, Optional<String> selected) implements CustomPacketPayload {
	public static final Type<ProjectsPayload> TYPE = new Type<>(MCVCS.id("projects"));
	public static final StreamCodec<ByteBuf, ProjectsPayload> STREAM_CODEC = StreamCodec.composite(
		ClientProject.STREAM_CODEC.apply(ByteBufCodecs.list()), ProjectsPayload::projects,
		ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), ProjectsPayload::selected,
		ProjectsPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
