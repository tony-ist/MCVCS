package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.util.Arrays;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.build.PreviewGrid;
import tony.mcvcs.client.browser.BuildBrowser;
import tony.mcvcs.client.browser.BuildBrowserKey;
import tony.mcvcs.client.browser.BuildBrowserScreen;
import tony.mcvcs.client.browser.PlacementPickerScreen;
import tony.mcvcs.client.browser.Thumbnail;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.network.BuildSummary;

/**
 * The builds overlay: {@code B} opens it with every build of the world in a grid, each showing its newest committed
 * version, name and tags; a build too big to download by itself waits for a click; a very long one arrives as a
 * coarser grid of itself; the refresh button picks up a new commit; the buttons a hovered cell shows select the build,
 * start {@code /vcs place} of it and teleport onto it, the first two asking which placement for a build with several and
 * greyed out for a build placed nowhere; and a player
 * who may not run {@code /vcs} is told so.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsBuildBrowserGameTest extends VcsGameTest {
	private static final String SMALL = "gametest-browser-tower";
	private static final String BIG = "gametest-browser-hall";
	/** Longer than {@link PreviewGrid#MAX_SIDE}, so its preview is sampled at every second block. */
	private static final String LONG = "gametest-browser-bridge";

	@Override
	protected void run(ClientGameTestContext context) {
		checkKey(context);

		resetBuilds(SMALL, BIG, LONG);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// Held still, so the screenshots show the same side every run; the big build's 48 blocks are past the limit.
			ClientConfig.override(new ClientConfig.Settings(0.0, ClientConfig.DEFAULT_CELL_SIZE, 40));

			// A 3x4x3 tower of stone with a grass top, glass in the middle and leaves on top, hovering over the ground.
			BlockPos towerMin = playerPos(singleplayer).offset(4, 3, 4);
			BlockPos towerMax = towerMin.offset(2, 3, 2);
			fillBox(singleplayer, towerMin, towerMax, Blocks.STONE_BRICKS.defaultBlockState(), towerMin, Blocks.STONE_BRICKS.defaultBlockState());
			fillBox(singleplayer, towerMin.above(), towerMax.below(2), Blocks.GLASS.defaultBlockState(), towerMin.above(), Blocks.GLASS.defaultBlockState());
			fillBox(singleplayer, towerMin.above(3), towerMax, Blocks.OAK_LEAVES.defaultBlockState(), towerMin.above(3), Blocks.GRASS_BLOCK.defaultBlockState());
			select(singleplayer, towerMin, towerMax);
			runCommand(context, "vcs create " + SMALL + " -we");
			read(schematic(SMALL, 1));

			// A 4x3x4 hall of planks: 48 blocks, too big to download without a click at this limit.
			BlockPos hallMin = playerPos(singleplayer).offset(-8, 3, 4);
			BlockPos hallMax = hallMin.offset(3, 2, 3);
			fillBox(singleplayer, hallMin, hallMax, Blocks.OAK_PLANKS.defaultBlockState(), hallMin, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, hallMin, hallMax);
			runCommand(context, "vcs create " + BIG + " -we");
			read(schematic(BIG, 1));

			// A 140x3x3 bridge of stone bricks with a gold stripe along its top: longer than a preview is ever sent whole,
			// so it arrives as a 70x2x2 grid of every second block.
			BlockPos bridgeMin = playerPos(singleplayer).offset(-70, 10, -12);
			BlockPos bridgeMax = bridgeMin.offset(139, 2, 2);
			fillBox(singleplayer, bridgeMin, bridgeMax, Blocks.STONE_BRICKS.defaultBlockState(), bridgeMin, Blocks.STONE_BRICKS.defaultBlockState());
			fillBox(singleplayer, bridgeMin.offset(0, 2, 1), bridgeMax.offset(0, 0, -1), Blocks.GOLD_BLOCK.defaultBlockState(), bridgeMin.offset(0, 2, 1), Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, bridgeMin, bridgeMax);
			runCommand(context, "vcs create " + LONG + " -we");
			read(schematic(LONG, 1));

			// A second version of the tower, tagged, so its cell shows the newest version with its tag.
			setBlock(singleplayer, towerMax, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs select " + SMALL);
			runCommand(context, "vcs commit 2.0.0");
			read(schematic(SMALL, 2));

			// B opens the overlay; the tower's preview downloads by itself, the hall waits for a click.
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitForScreen(BuildBrowserScreen.class);
			moveAway(context);
			waitForReady(context, SMALL, 2);
			assertVersion(context, SMALL, 2);
			if (context.computeOnClient(client -> BuildBrowser.thumbnail(summary(BIG)) != null)) {
				throw new AssertionError("Expected the hall's preview to wait for a click");
			}
			context.waitTicks(5);
			context.takeScreenshot("mcvcs-builds-overlay");

			// Clicking the hall's placeholder downloads its preview instead of placing it.
			click(context, BIG);
			waitForReady(context, BIG, 1);
			context.waitForScreen(BuildBrowserScreen.class);
			if (context.computeOnClient(client -> PreviewManager.place()) != null) {
				throw new AssertionError("Expected the first click on a placeholder to download the preview, not to place the build");
			}

			// The bridge is too long to send whole: it arrives as a grid of every second block, drawn at full size.
			click(context, LONG);
			waitForReady(context, LONG, 1);
			BuildBox grid = context.computeOnClient(client -> BuildBrowser.thumbnail(summary(LONG)).grid());
			int scale = context.computeOnClient(client -> BuildBrowser.thumbnail(summary(LONG)).scale());
			if (scale != 2 || grid == null || grid.sizeX() != 70 || grid.sizeY() != 2 || grid.sizeZ() != 2) {
				throw new AssertionError("Expected the bridge's preview as a 70x2x2 grid at scale 2 but got " + grid + " at scale " + scale);
			}

			// The previews turn: two frames a moment apart show them from different sides.
			moveAway(context);
			ClientConfig.override(new ClientConfig.Settings(90.0, ClientConfig.DEFAULT_CELL_SIZE, 40));
			context.waitTicks(5);
			context.takeScreenshot("mcvcs-builds-overlay-turning-1");
			context.waitTicks(10);
			context.takeScreenshot("mcvcs-builds-overlay-turning-2");
			ClientConfig.override(new ClientConfig.Settings(0.0, ClientConfig.DEFAULT_CELL_SIZE, 40));

			// A commit while the overlay is open shows up only once refresh is pressed.
			setBlock(singleplayer, towerMax.below(), Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(SMALL, 3));
			context.waitTicks(5);
			assertVersion(context, SMALL, 2);
			clickRefresh(context);
			context.waitFor(client -> version(SMALL) == 3);
			waitForReady(context, SMALL, 3);
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-refreshed");

			// Hovering a cell shows its buttons over the preview.
			hover(context, SMALL);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-buttons");

			// B again closes it.
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);

			// Select closes the overlay and selects the hall's only placement.
			open(context);
			clickButton(context, BIG, BuildBrowserScreen.SELECT);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> {
				ClientPlacement selected = ClientPlacements.selected();
				return selected != null && selected.build().equals(BIG);
			});

			// Place closes the overlay and starts placing the tower's newest version where the player stands.
			open(context);
			waitForReady(context, SMALL, 3);
			clickButton(context, SMALL, BuildBrowserScreen.PLACE);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> {
				PlacePreview place = PreviewManager.place();
				return place != null && place.blocks().name().equals(SMALL + "/p2") && place.blocks().version() == 3;
			});
			// The copy hangs below the player's feet, so they look down at it.
			context.runOnClient(client -> client.player.setXRot(90.0f));
			context.waitTicks(5);
			context.takeScreenshot("mcvcs-builds-overlay-placing");
			// Placed for good, so the tower has a second placement, p2, which the confirmation selects.
			runCommand(context, "vcs confirmPlace -f");
			context.waitFor(client -> ClientPlacements.all().stream().filter(placement -> placement.build().equals(SMALL)).count() == 2);
			context.runOnClient(client -> client.player.setXRot(0.0f));

			// With two placements, Select asks which one, headed with the build's name; Escape goes back to the overlay.
			open(context);
			clickButton(context, SMALL, BuildBrowserScreen.SELECT);
			context.waitForScreen(PlacementPickerScreen.class);
			String header = context.computeOnClient(client -> client.screen.getTitle().getString());
			if (!header.contains(SMALL)) {
				throw new AssertionError("Expected the placement picker's header to name " + SMALL + " but it says " + header);
			}
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-picker");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(BuildBrowserScreen.class);

			// Clicking a placement closes both and selects it.
			clickButton(context, SMALL, BuildBrowserScreen.SELECT);
			context.waitForScreen(PlacementPickerScreen.class);
			clickRow(context, Build.MAIN);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> {
				ClientPlacement selected = ClientPlacements.selected();
				return selected != null && selected.label().equals(SMALL + "/" + Build.MAIN);
			});

			// TP asks the same way and teleports on top of the placement picked.
			open(context);
			clickButton(context, SMALL, BuildBrowserScreen.TP);
			context.waitForScreen(PlacementPickerScreen.class);
			clickRow(context, Build.MAIN);
			context.waitFor(client -> client.screen == null);
			BlockPos onTower = new BlockPos(towerMin.getX() + 1, towerMax.getY() + 1, towerMin.getZ() + 1);
			context.waitFor(client -> client.player.blockPosition().equals(onTower));

			// TP closes the overlay and puts the player on top of the bridge, at its centre.
			open(context);
			clickButton(context, LONG, BuildBrowserScreen.TP);
			context.waitFor(client -> client.screen == null);
			BlockPos onBridge = new BlockPos(bridgeMin.getX() + 70, bridgeMax.getY() + 1, bridgeMin.getZ() + 1);
			context.waitFor(client -> client.player.blockPosition().equals(onBridge));

			// A build placed nowhere has Select and TP greyed out, and pressing one does nothing.
			runCommand(context, "vcs select " + BIG);
			runCommand(context, "vcs unplace -k");
			open(context);
			context.waitFor(client -> {
				BuildSummary hall = summary(BIG);
				return BuildBrowser.listState() == BuildBrowser.ListState.LOADED && hall != null && hall.placements() == 0;
			});
			hover(context, BIG);
			context.runOnClient(client -> {
				BuildBrowserScreen screen = (BuildBrowserScreen) client.screen;
				if (screen.buttonOf(BuildBrowserScreen.SELECT).active || screen.buttonOf(BuildBrowserScreen.TP).active || !screen.buttonOf(BuildBrowserScreen.PLACE).active) {
					throw new AssertionError("Expected only Place to be pressable on a build with no placements");
				}
			});
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-unplaced");
			clickButton(context, BIG, BuildBrowserScreen.SELECT);
			context.waitTicks(2);
			context.waitForScreen(BuildBrowserScreen.class);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);
		} finally {
			ClientConfig.load();
		}

		checkDenied(context);
	}

	/** The key is one of the game's key mappings, on {@code B} by default, with a readable name. */
	private static void checkKey(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (Arrays.stream(client.options.keyMappings).noneMatch(mapping -> mapping == BuildBrowserKey.KEY)) {
				throw new AssertionError("Expected the builds overlay key among the game's key mappings");
			}
			if (BuildBrowserKey.KEY.getDefaultKey().getValue() != GLFW.GLFW_KEY_B) {
				throw new AssertionError("Expected the builds overlay key to default to B but got " + BuildBrowserKey.KEY.getDefaultKey());
			}
			if (!I18n.exists(BuildBrowserKey.KEY.getName())) {
				throw new AssertionError("Expected a translation for " + BuildBrowserKey.KEY.getName());
			}
		});
	}

	/** In a world without cheats the player may not run {@code /vcs}, so the overlay lists nothing and says why. */
	private static void checkDenied(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(false)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.DENIED);
			context.takeScreenshot("mcvcs-builds-overlay-denied");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitFor(client -> client.screen == null);
		}
	}

	private static void waitForReady(ClientGameTestContext context, String name, int version) {
		context.waitFor(client -> {
			BuildSummary build = summary(name);
			if (build == null || build.version() != version) {
				return false;
			}
			Thumbnail thumbnail = BuildBrowser.thumbnail(build);
			if (thumbnail != null && thumbnail.state() == Thumbnail.State.FAILED) {
				throw new AssertionError("Preview of " + name + " v" + version + " failed: " + thumbnail.failure());
			}
			return thumbnail != null && thumbnail.state() == Thumbnail.State.READY;
		});
	}

	private static void assertVersion(ClientGameTestContext context, String name, int expected) {
		int actual = context.computeOnClient(client -> version(name));
		if (actual != expected) {
			throw new AssertionError("Expected the overlay to show " + name + " at v" + expected + " but it shows v" + actual);
		}
	}

	/** The version the overlay lists {@code name} at, or 0 if it does not list it. */
	private static int version(String name) {
		BuildSummary build = summary(name);
		return build == null ? 0 : build.version();
	}

	private static BuildSummary summary(String name) {
		return BuildBrowser.builds().stream().filter(build -> build.name().equals(name)).findFirst().orElse(null);
	}

	/** Opens the overlay with {@code B}. */
	private static void open(ClientGameTestContext context) {
		context.getInput().pressKey(BuildBrowserKey.KEY);
		context.waitForScreen(BuildBrowserScreen.class);
		context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.LOADED);
	}

	/** Clicks the top left corner of the preview of {@code name}'s cell, clear of its buttons, as a player would. */
	private static void click(ClientGameTestContext context, String name) {
		ScreenRectangle cell = cellOf(context, name);
		clickAt(context, cell.left() + 8, cell.top() + 8);
	}

	/** Moves the mouse onto the label of {@code name}'s cell, so its buttons show with the tooltip below them. */
	private static void hover(ClientGameTestContext context, String name) {
		ScreenRectangle cell = cellOf(context, name);
		moveTo(context, cell.left() + 8, cell.bottom() - 4);
		context.waitTick();
	}

	/** Hovers {@code name}'s cell and clicks its button labelled {@code label}. */
	private static void clickButton(ClientGameTestContext context, String name, String label) {
		hover(context, name);
		ScreenRectangle button = context.computeOnClient(client -> {
			Button found = client.screen instanceof BuildBrowserScreen screen ? screen.buttonOf(label) : null;
			if (found == null) {
				throw new AssertionError("Expected a " + label + " button on the cell of " + name);
			}
			return found.getRectangle();
		});
		clickAt(context, button.left() + button.width() / 2.0, button.top() + button.height() / 2.0);
	}

	/** Clicks the row of the placement called {@code name} in the placement picker. */
	private static void clickRow(ClientGameTestContext context, String name) {
		ScreenRectangle row = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof PlacementPickerScreen screen ? screen.rowOf(name) : null;
			if (found == null) {
				throw new AssertionError("Expected a row for " + name + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, row.left() + row.width() / 2.0, row.top() + row.height() / 2.0);
	}

	private static ScreenRectangle cellOf(ClientGameTestContext context, String name) {
		return context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof BuildBrowserScreen screen ? screen.cellOf(name) : null;
			if (found == null) {
				throw new AssertionError("Expected a cell for " + name + " on " + client.screen);
			}
			return found;
		});
	}

	/** Clicks the refresh button in the top right corner. */
	private static void clickRefresh(ClientGameTestContext context) {
		int width = context.computeOnClient(client -> client.getWindow().getGuiScaledWidth());
		clickAt(context, width - 8 - 30, 16);
	}

	/** Clicks at {@code (x, y)} in GUI coordinates. */
	private static void clickAt(ClientGameTestContext context, double x, double y) {
		moveTo(context, x, y);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Moves the mouse off every cell, so no tooltip covers the previews in a screenshot. */
	private static void moveAway(ClientGameTestContext context) {
		moveTo(context, 1, 1);
	}

	/** Moves the mouse to {@code (x, y)} in GUI coordinates, which the window scales up by the GUI scale. */
	private static void moveTo(ClientGameTestContext context, double x, double y) {
		double scale = context.computeOnClient(client -> {
			Window window = client.getWindow();
			return (double) window.getScreenWidth() / window.getGuiScaledWidth();
		});
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTick();
	}
}
