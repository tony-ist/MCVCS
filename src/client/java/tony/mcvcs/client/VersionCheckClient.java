package tony.mcvcs.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import tony.mcvcs.client.browser.BuildBrowser;
import tony.mcvcs.network.ModVersionPayload;
import tony.mcvcs.network.VersionCheck;

/**
 * Client side of {@link VersionCheck}: tells a server with the mod which version the client has. A server with the
 * mod but too old to take it cannot do the comparison, so the client warns on its own.
 */
public final class VersionCheckClient {
	private VersionCheckClient() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (ClientPlayNetworking.canSend(ModVersionPayload.TYPE)) {
				sender.sendPacket(new ModVersionPayload(VersionCheck.version()));
			} else if (BuildBrowser.serverSupported()) {
				client.player.sendSystemMessage(VersionCheck.mismatch(null, VersionCheck.version()));
			}
		});
	}
}
