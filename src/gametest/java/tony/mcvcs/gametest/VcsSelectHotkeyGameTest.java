package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.util.Arrays;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.selection.SelectHotkey;
import tony.mcvcs.build.ClientPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * The select hotkey is listed under the game's key binds, selects the build under the crosshair, and leaves the
 * selection alone when there is none.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsSelectHotkeyGameTest extends VcsGameTest {
	private static final String FIRST = "gametest-hotkey-first";
	private static final String SECOND = "gametest-hotkey-second";

	@Override
	protected void run(ClientGameTestContext context) {
		checkKeyBindsScreen(context);

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(FIRST, SECOND);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Two 3x2x2 stone boxes: the first in front of and to the right of the player, the second to the left.
			BlockPos firstMin = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos firstMax = firstMin.offset(2, 1, 1);
			BlockPos secondMin = playerPos(singleplayer).offset(-4, 0, 2);
			BlockPos secondMax = secondMin.offset(2, 1, 1);

			fillBox(singleplayer, firstMin, firstMax, Blocks.STONE.defaultBlockState(), firstMin, Blocks.STONE.defaultBlockState());
			fillBox(singleplayer, secondMin, secondMax, Blocks.STONE.defaultBlockState(), secondMin, Blocks.STONE.defaultBlockState());

			select(singleplayer, firstMin, firstMax);
			runCommand(context, "vcs create " + FIRST + " -we");
			read(schematic(FIRST, 1));
			select(singleplayer, secondMin, secondMax);
			runCommand(context, "vcs create " + SECOND + " -we");
			read(schematic(SECOND, 1));
			// Creating selects, so the second build is the one selected now.
			waitForSelection(context, SECOND);

			// Looking at the first build and pressing the key selects it.
			lookAt(context, firstMin, firstMax);
			context.getInput().pressKey(SelectHotkey.KEY);
			waitForSelection(context, FIRST);
			screenshotLastFrame(context, "mcvcs-vcs-select-hotkey");

			// Looking at the sky, there is no build to select, so the selection stays.
			context.runOnClient(client -> client.player.setXRot(-90.0f));
			context.waitTicks(2);
			context.getInput().pressKey(SelectHotkey.KEY);
			context.waitTicks(5);
			assertSelected(context, FIRST);

			// Pressing the key on the build that is already selected keeps it selected.
			lookAt(context, firstMin, firstMax);
			context.getInput().pressKey(SelectHotkey.KEY);
			context.waitTicks(5);
			assertSelected(context, FIRST);

			// The second build is behind the player's left shoulder, so turning to it and pressing selects it.
			lookAt(context, secondMin, secondMax);
			context.getInput().pressKey(SelectHotkey.KEY);
			waitForSelection(context, SECOND);

			// Standing inside the first build's box, looking away from everything, still selects the build stood in.
			BlockPos inside = firstMin.offset(1, 0, 0);
			singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0)
				.teleportTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5));
			context.waitFor(client -> client.player.blockPosition().equals(inside));
			context.runOnClient(client -> client.player.setXRot(-90.0f));
			context.waitTicks(2);
			context.getInput().pressKey(SelectHotkey.KEY);
			waitForSelection(context, FIRST);
		}
	}

	/**
	 * The key is one of the game's own key mappings, so it can be rebound under Options, Controls, Key Binds, where it
	 * and its category have readable names. Mod categories are listed after vanilla's, so the list is scrolled to the
	 * end for the screenshot.
	 */
	private static void checkKeyBindsScreen(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (Arrays.stream(client.options.keyMappings).noneMatch(mapping -> mapping == SelectHotkey.KEY)) {
				throw new AssertionError("Expected the select hotkey among the game's key mappings");
			}
			for (String key : new String[] {SelectHotkey.KEY.getName(), SelectHotkey.KEY.getCategory().id().toLanguageKey("key.category")}) {
				if (!I18n.exists(key)) {
					throw new AssertionError("Expected a translation for " + key);
				}
			}
		});

		context.setScreen(() -> new KeyBindsScreen(null, Minecraft.getInstance().options));
		context.waitForScreen(KeyBindsScreen.class);
		context.runOnClient(client -> {
			KeyBindsList list = client.screen.children().stream()
				.filter(KeyBindsList.class::isInstance).map(KeyBindsList.class::cast).findFirst()
				.orElseThrow(() -> new AssertionError("Expected a key binds list on " + client.screen));
			list.setScrollAmount(list.maxScrollAmount());
		});
		context.waitTicks(2);
		context.takeScreenshot("mcvcs-vcs-select-hotkey-key-binds");

		context.setScreen(TitleScreen::new);
		context.waitForScreen(TitleScreen.class);
	}

	private static void waitForSelection(ClientGameTestContext context, String name) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name);
		});
	}

	private static void assertSelected(ClientGameTestContext context, String name) {
		ClientPlacement selected = context.computeOnClient(client -> ClientPlacements.selected());
		if (selected == null) {
			throw new AssertionError("Expected selection '" + name + "' but nothing is selected");
		}
		if (!selected.build().equals(name)) {
			throw new AssertionError("Expected selection '" + name + "' but got '" + selected.label() + "'");
		}
	}
}
