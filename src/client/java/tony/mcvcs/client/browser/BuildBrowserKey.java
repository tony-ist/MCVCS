package tony.mcvcs.client.browser;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import tony.mcvcs.client.selection.SelectHotkey;

/**
 * The key that opens the builds overlay, {@code B} unless rebound under Options, Controls, Key Binds in the MCVCS
 * category. Pressing it again while the overlay is open closes it, see {@link BuildBrowserScreen#keyPressed}.
 */
public final class BuildBrowserKey {
	/** Named by {@code key.mcvcs.open_builds}; {@code B}, which nothing in vanilla uses. */
	public static final KeyMapping KEY = new KeyMapping("key.mcvcs.open_builds", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, SelectHotkey.CATEGORY);

	private BuildBrowserKey() {
	}

	/** Must run from the client entrypoint: key mappings can only be added before the options are loaded. */
	public static void register() {
		KeyMappingHelper.registerKeyMapping(KEY);
		ClientTickEvents.END_CLIENT_TICK.register(BuildBrowserKey::tick);
	}

	private static void tick(Minecraft client) {
		while (KEY.consumeClick()) {
			open(client);
		}
	}

	/** Opens the overlay, or says on the action bar why it cannot be opened on this server. */
	public static void open(Minecraft client) {
		if (client.player == null || client.screen != null) {
			return;
		}
		if (!BuildBrowser.serverSupported()) {
			client.player.sendOverlayMessage(Component.literal("This server does not have MCVCS installed, or has an older version of it"));
			return;
		}
		client.setScreen(new BuildBrowserScreen());
	}
}
