package tony.mcvcs.gametest;

import java.nio.file.Files;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.TitleScreen;

import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.config.ClientConfigScreen;

/**
 * The settings screen Mod Menu opens: it shows every setting, a click on a toggle flips it, and closing the screen
 * puts the settings in force and writes them to {@code config/mcvcs.json}.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsClientConfigScreenGameTest extends VcsGameTest {
	@Override
	protected void run(ClientGameTestContext context) {
		try {
			context.runOnClient(client -> ClientConfig.save(ClientConfig.Settings.DEFAULT));
			context.runOnClient(client -> client.gui.setScreen(new ClientConfigScreen(client.gui.screen())));
			context.waitForScreen(ClientConfigScreen.class);
			context.takeScreenshot("mcvcs-config-screen");

			// Labels off: the screen shows it at once, the game only once the screen is closed.
			ScreenRectangle labels = context.computeOnClient(client -> ((ClientConfigScreen) client.gui.screen()).showLabelsButton());
			if (labels == null) {
				throw new AssertionError("Expected a Build Labels toggle on the settings screen");
			}
			clickAt(context, labels.left() + labels.width() / 2.0, labels.top() + labels.height() / 2.0);
			context.runOnClient(client -> {
				if (((ClientConfigScreen) client.gui.screen()).settings().showLabels()) {
					throw new AssertionError("Expected Build Labels to be off after clicking it");
				}
				if (!ClientConfig.settings().showLabels()) {
					throw new AssertionError("Expected labels to stay on until the screen is closed");
				}
			});
			context.takeScreenshot("mcvcs-config-screen-labels-off");

			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(TitleScreen.class);
			context.runOnClient(client -> {
				if (!ClientConfig.settings().equals(ClientConfig.Settings.DEFAULT.withDisplay(false, true))) {
					throw new AssertionError("Expected only labels switched off once the screen is closed but got " + ClientConfig.settings());
				}
				ClientConfig.load();
				if (ClientConfig.settings().showLabels()) {
					throw new AssertionError("Expected labels off in " + ClientConfig.file() + " but got " + ClientConfig.settings());
				}
			});
			String json = context.computeOnClient(client -> {
				try {
					return Files.readString(ClientConfig.file());
				} catch (java.io.IOException e) {
					throw new AssertionError("Could not read " + ClientConfig.file(), e);
				}
			});
			if (!json.contains("\"showLabels\": false")) {
				throw new AssertionError("Expected \"showLabels\": false in the config file but it holds " + json);
			}
		} finally {
			// Back to the defaults, so the other tests see labels.
			context.runOnClient(client -> ClientConfig.save(ClientConfig.Settings.DEFAULT));
		}
	}

	private static void clickAt(ClientGameTestContext context, double x, double y) {
		double scale = context.computeOnClient(client -> {
			Window window = client.getWindow();
			return (double) window.getScreenWidth() / window.getGuiScaledWidth();
		});
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTick();
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}
}
