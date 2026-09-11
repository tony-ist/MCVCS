package tony.mcvcs.network;

import java.util.Optional;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import tony.mcvcs.project.SelectedProject;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the project the player has selected, or empty when there is none.
 * Replaces whatever the client knew before.
 */
public record SelectedProjectPayload(Optional<SelectedProject> selected) implements CustomPacketPayload {
	public static final Type<SelectedProjectPayload> TYPE = new Type<>(MCVCS.id("selected_project"));
	public static final StreamCodec<ByteBuf, SelectedProjectPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.optional(SelectedProject.STREAM_CODEC), SelectedProjectPayload::selected,
		SelectedProjectPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
