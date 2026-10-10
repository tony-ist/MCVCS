package tony.mcvcs.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import tony.mcvcs.MCVCS;

/**
 * Warns a player whose client has a different version of the mod than the server. The client sends its version on
 * join in a {@link ModVersionPayload}; the server answers a mismatch with a chat warning. Everything keeps working,
 * as far as the two versions understand each other.
 */
public final class VersionCheck {
	private VersionCheck() {
	}

	/** Registers the payload type and the receiver; must run on both sides, so it belongs in the main entrypoint. */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(ModVersionPayload.TYPE, ModVersionPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ModVersionPayload.TYPE, (payload, context) -> {
			if (!payload.version().equals(version())) {
				context.player().sendSystemMessage(mismatch(version(), payload.version()));
			}
		});
	}

	/** The version of the mod on this side, e.g. {@code 1.2.0}. */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MCVCS.MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
	}

	/**
	 * The warning for a client on {@code clientVersion} joining a server on {@code serverVersion}, or on a version too
	 * old to say which when {@code serverVersion} is {@code null}.
	 */
	public static MutableComponent mismatch(String serverVersion, String clientVersion) {
		String server = serverVersion == null ? "an older version" : serverVersion;
		return Component.literal("This server runs MCVCS " + server + " but you have " + clientVersion
			+ "; some features may not work as expected.").withStyle(ChatFormatting.YELLOW);
	}
}
