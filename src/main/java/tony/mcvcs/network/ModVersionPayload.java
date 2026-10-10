package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import io.netty.buffer.ByteBuf;

/** Client to server, on join: the version of the mod the client has, so the server can warn about a mismatch. */
public record ModVersionPayload(String version) implements CustomPacketPayload {
	public static final Type<ModVersionPayload> TYPE = new Type<>(MCVCS.id("mod_version"));
	public static final StreamCodec<ByteBuf, ModVersionPayload> STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(ModVersionPayload::new, ModVersionPayload::version);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
