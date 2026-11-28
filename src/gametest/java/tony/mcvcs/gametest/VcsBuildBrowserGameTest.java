package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.create;
import static tony.mcvcs.gametest.VcsTestSupport.blockAt;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;
import java.util.Arrays;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.build.PreviewGrid;
import tony.mcvcs.client.browser.BuildBrowser;
import tony.mcvcs.client.browser.BuildBrowserKey;
import tony.mcvcs.client.browser.BuildBrowserScreen;
import tony.mcvcs.client.browser.CheckoutScreen;
import tony.mcvcs.client.browser.CreateBuildScreen;
import tony.mcvcs.client.browser.DeleteBuildScreen;
import tony.mcvcs.client.browser.PlacementPickerScreen;
import tony.mcvcs.client.browser.RenameBuildScreen;
import tony.mcvcs.client.browser.Thumbnail;
import tony.mcvcs.client.browser.VersionPickerScreen;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.selection.PendingClickHighlight;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.network.BuildSummary;

/**
 * The builds overlay: {@code B} opens it with every build of the world in a grid, each showing its newest committed
 * version, name and tags; a build too big to download by itself waits for a click; a very long one arrives as a
 * coarser grid of itself; reopening it picks up a new commit; Checkout checks out a version into the selected
 * placement, see {@link #checkCheckout}; the buttons a hovered cell shows select the build,
 * start {@code /vcs place} of it and teleport onto it, Place asking which version for a build with several and the
 * other two asking which placement for a build with several and
 * greyed out for a build placed nowhere; the three dots' menu renames a build to a valid new name and deletes a build
 * once asked, emptying its placements only if ticked to; Create asks for a name and starts {@code /vcs create}, with {@code -b} if ticked to; and a player who may not run {@code /vcs} is told so.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsBuildBrowserGameTest extends VcsGameTest {
	private static final String SMALL = "gametest-browser-tower";
	private static final String BIG = "gametest-browser-hall";
	/** Longer than {@link PreviewGrid#MAX_SIDE}, so its preview is sampled at every second block. */
	private static final String LONG = "gametest-browser-bridge";
	/** Named in the create window, then made directly, since no block is punched. */
	private static final String NEW = "gametest-browser-new";
	/** What {@link #BIG} is renamed to through its menu. */
	private static final String RENAMED = "gametest-browser-palace";
	/** In the way of {@link #NEW}, so creating it there fails. */
	private static final String SHED = "gametest-browser-shed";

	@Override
	protected void run(ClientGameTestContext context) {
		checkKey(context);

		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// Held still, so the screenshots show the same side every run; the big build's 48 blocks are past the limit.
			ClientConfig.override(new ClientConfig.Settings(0.0, ClientConfig.DEFAULT_CELL_SIZE, 40, true, true));

			// A 3x4x3 tower of stone with a grass top, glass in the middle and leaves on top, hovering over the ground.
			BlockPos towerMin = playerPos(singleplayer).offset(4, 3, 4);
			BlockPos towerMax = towerMin.offset(2, 3, 2);
			fillBox(singleplayer, towerMin, towerMax, Blocks.STONE_BRICKS.defaultBlockState(), towerMin, Blocks.STONE_BRICKS.defaultBlockState());
			fillBox(singleplayer, towerMin.above(), towerMax.below(2), Blocks.GLASS.defaultBlockState(), towerMin.above(), Blocks.GLASS.defaultBlockState());
			// Persistent, or with no log near they decay at random during the test.
			BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			fillBox(singleplayer, towerMin.above(3), towerMax, leaves, towerMin.above(3), Blocks.GRASS_BLOCK.defaultBlockState());
			create(context, singleplayer, SMALL, towerMin, towerMax);
			read(schematic(SMALL, 1));

			// A 4x3x4 hall of planks: 48 blocks, too big to download without a click at this limit.
			BlockPos hallMin = playerPos(singleplayer).offset(-8, 3, 4);
			BlockPos hallMax = hallMin.offset(3, 2, 3);
			fillBox(singleplayer, hallMin, hallMax, Blocks.OAK_PLANKS.defaultBlockState(), hallMin, Blocks.GOLD_BLOCK.defaultBlockState());
			create(context, singleplayer, BIG, hallMin, hallMax);
			read(schematic(BIG, 1));

			// A 140x3x3 bridge of stone bricks with a gold stripe along its top: longer than a preview is ever sent whole,
			// so it arrives as a 70x2x2 grid of every second block.
			BlockPos bridgeMin = playerPos(singleplayer).offset(-70, 10, -12);
			BlockPos bridgeMax = bridgeMin.offset(139, 2, 2);
			fillBox(singleplayer, bridgeMin, bridgeMax, Blocks.STONE_BRICKS.defaultBlockState(), bridgeMin, Blocks.STONE_BRICKS.defaultBlockState());
			fillBox(singleplayer, bridgeMin.offset(0, 2, 1), bridgeMax.offset(0, 0, -1), Blocks.GOLD_BLOCK.defaultBlockState(), bridgeMin.offset(0, 2, 1), Blocks.GOLD_BLOCK.defaultBlockState());
			create(context, singleplayer, LONG, bridgeMin, bridgeMax);
			read(schematic(LONG, 1));

			// A second version of the tower, tagged, so its cell shows the newest version with its tag.
			setBlock(singleplayer, towerMax, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs select " + SMALL);
			runCommand(context, "vcs save 2.0.0");
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
			if (context.computeOnClient(client -> visibleHeaderButton(client, BuildBrowserScreen.CANCEL_PLACE)) != null) {
				throw new AssertionError("Expected no Cancel place button while nothing is being placed");
			}

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
			ClientConfig.override(new ClientConfig.Settings(90.0, ClientConfig.DEFAULT_CELL_SIZE, 40, true, true));
			context.waitTicks(5);
			context.takeScreenshot("mcvcs-builds-overlay-turning-1");
			context.waitTicks(10);
			context.takeScreenshot("mcvcs-builds-overlay-turning-2");
			ClientConfig.override(new ClientConfig.Settings(0.0, ClientConfig.DEFAULT_CELL_SIZE, 40, true, true));

			// A commit while the overlay is open shows up only once it is opened again; it has no refresh button.
			if (context.computeOnClient(client -> headerButton(client, "Refresh")) != null) {
				throw new AssertionError("Expected no Refresh button in the overlay");
			}
			assertLeftOf(context, BuildBrowserScreen.CHECKOUT, BuildBrowserScreen.CREATE);
			setBlock(singleplayer, towerMax.below(), Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs save");
			read(schematic(SMALL, 3));
			context.waitTicks(5);
			assertVersion(context, SMALL, 2);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);
			open(context);
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

			// The hall has only one version, so Place closes the overlay and starts placing it straight away.
			open(context);
			clickButton(context, BIG, BuildBrowserScreen.PLACE);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> {
				PlacePreview place = PreviewManager.place();
				return place != null && place.blocks().name().equals(BIG + "/p2") && place.blocks().version() == 1;
			});
			// While it is being placed, the overlay shows Cancel place left of Create, which drops the copy and hides itself.
			open(context);
			context.waitFor(client -> visibleHeaderButton(client, BuildBrowserScreen.CANCEL_PLACE) != null);
			assertLeftOf(context, BuildBrowserScreen.CANCEL_PLACE, BuildBrowserScreen.CREATE);
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-cancel-place");
			clickHeader(context, BuildBrowserScreen.CANCEL_PLACE);
			context.waitFor(client -> PreviewManager.place() == null);
			context.waitFor(client -> visibleHeaderButton(client, BuildBrowserScreen.CANCEL_PLACE) == null);
			context.waitForScreen(BuildBrowserScreen.class);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);

			// The tower has three, so Place asks which, newest first, each with its preview and tags.
			open(context);
			waitForReady(context, SMALL, 3);
			clickButton(context, SMALL, BuildBrowserScreen.PLACE);
			context.waitForScreen(VersionPickerScreen.class);
			context.waitFor(client -> {
				BuildSummary tower = summary(SMALL);
				return tower.versions().stream().allMatch(version -> {
					Thumbnail thumbnail = BuildBrowser.thumbnail(tower, version);
					return thumbnail != null && thumbnail.state() == Thumbnail.State.READY;
				});
			});
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-versions");
			// Escape goes back to the overlay.
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(BuildBrowserScreen.class);

			// Clicking a version closes both and starts placing it where the player stands.
			clickButton(context, SMALL, BuildBrowserScreen.PLACE);
			context.waitForScreen(VersionPickerScreen.class);
			clickVersion(context, 2);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> {
				PlacePreview place = PreviewManager.place();
				return place != null && place.blocks().name().equals(SMALL + "/p2") && place.blocks().version() == 2;
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

			checkCheckout(context, singleplayer, towerMin, towerMax);

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
			// Nothing is selected any more, so Checkout is greyed out.
			context.waitFor(client -> ClientPlacements.selected() == null);
			context.waitFor(client -> !headerButton(client, BuildBrowserScreen.CHECKOUT).active);
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

			// The three dots in a hovered cell's corner open its menu; Escape closes only the menu.
			clickButton(context, LONG, BuildBrowserScreen.MORE);
			context.waitFor(client -> client.screen instanceof BuildBrowserScreen screen && screen.buttonOf(BuildBrowserScreen.DELETE) != null);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-menu");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitFor(client -> client.screen instanceof BuildBrowserScreen screen && screen.buttonOf(BuildBrowserScreen.DELETE) == null);

			// Delete asks first, with the box to also empty the placements unticked; Cancel goes back and keeps the build.
			openDelete(context, LONG);
			String question = context.computeOnClient(client -> client.screen.getTitle().getString());
			if (!question.equals("Delete " + LONG + "?")) {
				throw new AssertionError("Expected the delete window to ask about " + LONG + " but it says " + question);
			}
			context.runOnClient(client -> {
				Checkbox checkbox = client.screen.children().stream().filter(Checkbox.class::isInstance).map(Checkbox.class::cast).findFirst().orElseThrow();
				if (checkbox.selected()) {
					throw new AssertionError("Expected the box to also delete the placed blocks to start unticked");
				}
			});
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-delete");
			clickWidget(context, DeleteBuildScreen.CANCEL);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitTicks(5);
			if (context.computeOnClient(client -> summary(LONG)) == null || !Files.exists(BuildStorage.directory(LONG))) {
				throw new AssertionError("Expected Cancel to keep " + LONG);
			}

			// Ticked, deleting the bridge empties its box too, and the overlay comes back without it.
			openDelete(context, LONG);
			clickWidget(context, DeleteBuildScreen.CLEAR);
			clickWidget(context, DeleteBuildScreen.DELETE);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.LOADED && summary(LONG) == null);
			if (Files.exists(BuildStorage.directory(LONG))) {
				throw new AssertionError("Expected " + LONG + "'s folder to be gone");
			}
			if (!blockAt(singleplayer, bridgeMin).isAir() || !blockAt(singleplayer, bridgeMax).isAir()) {
				throw new AssertionError("Expected deleting " + LONG + " with the box ticked to empty its placement");
			}

			// Unticked, deleting the tower leaves its blocks standing.
			openDelete(context, SMALL);
			clickWidget(context, DeleteBuildScreen.DELETE);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.LOADED && summary(SMALL) == null);
			if (Files.exists(BuildStorage.directory(SMALL))) {
				throw new AssertionError("Expected " + SMALL + "'s folder to be gone");
			}
			if (!blockAt(singleplayer, towerMin).is(Blocks.STONE_BRICKS)) {
				throw new AssertionError("Expected deleting " + SMALL + " with the box unticked to leave its blocks, but found " + blockAt(singleplayer, towerMin));
			}
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-deleted");

			// Rename opens with the build's name, Save greyed out until it is a new, valid one; B typed into the field is just a letter.
			openRename(context, BIG);
			assertRenameField(context, BIG, false);
			context.getInput().pressKey(GLFW.GLFW_KEY_B);
			context.waitTicks(2);
			if (context.computeOnClient(client -> !(client.screen instanceof RenameBuildScreen))) {
				throw new AssertionError("Expected B typed into the rename field to leave the window open");
			}
			setRenameField(context, "1" + RENAMED);
			assertRenameField(context, "1" + RENAMED, false);
			setRenameField(context, RENAMED);
			assertRenameField(context, RENAMED, true);
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-rename");
			// Cancel keeps the old name.
			clickRenameWidget(context, RenameBuildScreen.CANCEL);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitTicks(5);
			if (context.computeOnClient(client -> summary(BIG)) == null) {
				throw new AssertionError("Expected Cancel to keep " + BIG);
			}
			// Enter saves, and the overlay comes back listing the build under its new name.
			openRename(context, BIG);
			setRenameField(context, RENAMED);
			context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.LOADED && summary(BIG) == null && summary(RENAMED) != null);
			if (!Files.isRegularFile(BuildStorage.buildFile(RENAMED)) || Files.exists(BuildStorage.directory(BIG))) {
				throw new AssertionError("Expected " + BIG + "'s folder to be moved to " + RENAMED);
			}

			// Create asks for a name, refusing a bad one; B typed into the field is just a letter.
			clickHeader(context, BuildBrowserScreen.CREATE);
			context.waitForScreen(CreateBuildScreen.class);
			context.getInput().typeChars("1bad");
			context.getInput().pressKey(GLFW.GLFW_KEY_B);
			context.waitTicks(2);
			if (context.computeOnClient(client -> !(client.screen instanceof CreateBuildScreen screen) || screen.canCreate())) {
				throw new AssertionError("Expected the create window to stay open with Create greyed out for a name starting with a digit");
			}
			context.runOnClient(client -> client.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue(""));
			context.getInput().typeChars(NEW);
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-create");
			// Unticked, it waits for one punched block.
			clickCreateWidget(context, CreateBuildScreen.CREATE);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> PendingClickHighlight.armed() && PendingClickHighlight.corner() == null);
			assertActionBar(context, "Hit any block of your build to save it as " + NEW);

			// While it waits, the overlay's Create button cancels it instead, and turns back once it has.
			open(context);
			context.waitFor(client -> headerButton(client, BuildBrowserScreen.CANCEL_CREATION) != null);
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-cancel-creation");
			clickHeader(context, BuildBrowserScreen.CANCEL_CREATION);
			context.waitFor(client -> !PendingClickHighlight.armed() && headerButton(client, BuildBrowserScreen.CREATE) != null);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);

			// Not created yet, so the window opens with the name kept; ticked, it waits for two corners instead.
			openCreate(context);
			assertDraft(context, NEW, false);
			clickCreateWidget(context, CreateBuildScreen.BOX);
			clickCreateWidget(context, CreateBuildScreen.CREATE);
			context.waitFor(client -> client.screen == null);
			context.waitTicks(5);
			assertActionBar(context, "Hit one corner of " + NEW);
			// Typed, the command cancels it just the same.
			runCommand(context, "vcs cancelCreate");
			context.waitFor(client -> !PendingClickHighlight.armed());

			// Refused for overlapping another build, it keeps the name and the ticked box.
			BlockPos shedMin = playerPos(singleplayer).offset(4, 3, -8);
			BlockPos shedMax = shedMin.offset(1, 1, 1);
			fillBox(singleplayer, shedMin, shedMax, Blocks.OAK_PLANKS.defaultBlockState(), shedMin, Blocks.OAK_PLANKS.defaultBlockState());
			create(context, singleplayer, SHED, shedMin, shedMax);
			create(context, singleplayer, NEW, shedMin, shedMax);
			openCreate(context);
			assertDraft(context, NEW, true);
			moveAway(context);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-builds-overlay-create-kept");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(BuildBrowserScreen.class);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);

			// Created, the window starts empty and unticked again.
			create(context, singleplayer, NEW, shedMin.offset(0, 4, 0), shedMax.offset(0, 4, 0));
			openCreate(context);
			assertDraft(context, "", false);
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

	/** Opens the menu of {@code name}'s cell and clicks its Delete, which asks whether to delete it. */
	private static void openDelete(ClientGameTestContext context, String name) {
		clickButton(context, name, BuildBrowserScreen.MORE);
		clickButton(context, name, BuildBrowserScreen.DELETE);
		context.waitForScreen(DeleteBuildScreen.class);
	}

	/** Opens the menu of {@code name}'s cell and clicks its Rename, which opens the rename window. */
	private static void openRename(ClientGameTestContext context, String name) {
		clickButton(context, name, BuildBrowserScreen.MORE);
		clickButton(context, name, BuildBrowserScreen.RENAME);
		context.waitForScreen(RenameBuildScreen.class);
	}

	private static void setRenameField(ClientGameTestContext context, String value) {
		context.runOnClient(client -> client.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue(value));
	}

	/** The rename window's field holds {@code name}, and Save can be pressed or not as {@code canSave} says. */
	private static void assertRenameField(ClientGameTestContext context, String name, boolean canSave) {
		context.runOnClient(client -> {
			if (!(client.screen instanceof RenameBuildScreen screen)) {
				throw new AssertionError("Expected the rename window but found " + client.screen);
			}
			String field = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().getValue();
			if (!field.equals(name) || screen.canPressSave() != canSave) {
				throw new AssertionError("Expected the rename window to show '" + name + "' with Save " + (canSave ? "pressable" : "greyed out")
					+ " but it shows '" + field + "' with Save " + (screen.canPressSave() ? "pressable" : "greyed out"));
			}
		});
	}

	/** Clicks the button labelled {@code label} in the rename window. */
	private static void clickRenameWidget(ClientGameTestContext context, String label) {
		ScreenRectangle widget = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof RenameBuildScreen screen ? screen.widgetOf(label) : null;
			if (found == null) {
				throw new AssertionError("Expected " + label + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, widget.left() + widget.width() / 2.0, widget.top() + widget.height() / 2.0);
	}

	/** Clicks the button or checkbox labelled {@code label} in the delete window. */
	private static void clickWidget(ClientGameTestContext context, String label) {
		ScreenRectangle widget = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof DeleteBuildScreen screen ? screen.widgetOf(label) : null;
			if (found == null) {
				throw new AssertionError("Expected " + label + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, widget.left() + widget.width() / 2.0, widget.top() + widget.height() / 2.0);
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

	/** Clicks the cell of version {@code version} in the version picker. */
	private static void clickVersion(ClientGameTestContext context, int version) {
		ScreenRectangle cell = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof VersionPickerScreen screen ? screen.cellOf(version) : null;
			if (found == null) {
				throw new AssertionError("Expected a cell for v" + version + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, cell.left() + cell.width() / 2.0, cell.top() + cell.height() / 2.0);
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

	/** Clicks the button labelled {@code label} in the overlay's header. */
	private static void clickHeader(ClientGameTestContext context, String label) {
		ScreenRectangle button = context.computeOnClient(client -> {
			Button found = headerButton(client, label);
			if (found == null) {
				throw new AssertionError("Expected a " + label + " button on " + client.screen);
			}
			return found.getRectangle();
		});
		clickAt(context, button.left() + button.width() / 2.0, button.top() + button.height() / 2.0);
	}

	/** The overlay's header button labelled {@code label}, or null if the overlay has none. */
	private static Button headerButton(Minecraft client, String label) {
		return client.screen instanceof BuildBrowserScreen screen ? screen.children().stream()
			.filter(child -> child instanceof Button found && found.getMessage().getString().equals(label))
			.map(Button.class::cast).findFirst().orElse(null) : null;
	}

	/** The overlay's header button labelled {@code label} if it is shown, or null. */
	private static Button visibleHeaderButton(Minecraft client, String label) {
		Button button = headerButton(client, label);
		return button != null && button.visible ? button : null;
	}

	/** The header button labelled {@code left} sits to the left of the one labelled {@code right}. */
	private static void assertLeftOf(ClientGameTestContext context, String left, String right) {
		context.runOnClient(client -> {
			if (headerButton(client, left).getRight() > headerButton(client, right).getX()) {
				throw new AssertionError("Expected " + left + " to the left of " + right);
			}
		});
	}

	/** Opens the overlay and its create window. */
	private static void openCreate(ClientGameTestContext context) {
		open(context);
		clickHeader(context, BuildBrowserScreen.CREATE);
		context.waitForScreen(CreateBuildScreen.class);
	}

	/** The create window shows {@code name} with the box ticked or not as {@code box} says. */
	private static void assertDraft(ClientGameTestContext context, String name, boolean box) {
		context.runOnClient(client -> {
			String field = client.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().getValue();
			boolean ticked = client.screen.children().stream().filter(Checkbox.class::isInstance).map(Checkbox.class::cast).findFirst().orElseThrow().selected();
			if (!field.equals(name) || ticked != box) {
				throw new AssertionError("Expected the create window to show '" + name + "' with the box " + (box ? "ticked" : "unticked")
					+ " but it shows '" + field + "' with the box " + (ticked ? "ticked" : "unticked"));
			}
		});
	}

	/** Clicks the button or checkbox labelled {@code label} in the create window. */
	private static void clickCreateWidget(ClientGameTestContext context, String label) {
		ScreenRectangle widget = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof CreateBuildScreen screen ? screen.widgetOf(label) : null;
			if (found == null) {
				throw new AssertionError("Expected " + label + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, widget.left() + widget.width() / 2.0, widget.top() + widget.height() / 2.0);
	}

	private static void assertActionBar(ClientGameTestContext context, String expected) {
		String message = context.computeOnClient(VcsTestSupport::overlayMessage);
		if (!expected.equals(message)) {
			throw new AssertionError("Expected the action bar to say '" + expected + "' but it says '" + message + "'");
		}
	}

	/**
	 * With the tower's main placement selected, holding v3, and one of its blocks changed: Checkout names the build and
	 * the placement, shows every version with v3 marked current, says one block is modified and greys out its button
	 * until the unticked override box is ticked; Show modified blocks runs {@code /vcs changes}; ticked, checking out v1
	 * puts it into the placement. With nothing modified, Show modified blocks is gone and v3 goes back without ticking.
	 */
	private static void checkCheckout(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos towerMin, BlockPos towerMax) {
		String label = SMALL + "/" + Build.MAIN;
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.label().equals(label) && selected.head() == 3;
		});
		setBlock(singleplayer, towerMin, Blocks.EMERALD_BLOCK.defaultBlockState());

		openCheckout(context);
		context.waitFor(client -> ((CheckoutScreen) client.screen).modified() == 1);
		context.waitFor(client -> {
			BuildSummary tower = summary(SMALL);
			return tower.versions().stream().allMatch(version -> {
				Thumbnail thumbnail = BuildBrowser.thumbnail(tower, version);
				return thumbnail != null && thumbnail.state() == Thumbnail.State.READY;
			});
		});
		context.runOnClient(client -> {
			CheckoutScreen screen = (CheckoutScreen) client.screen;
			String title = screen.getTitle().getString();
			if (!title.contains(SMALL) || !title.contains(Build.MAIN)) {
				throw new AssertionError("Expected the checkout window to name " + SMALL + " and " + Build.MAIN + " but it says " + title);
			}
			if (((Checkbox) screen.widgetOf(CheckoutScreen.OVERRIDE)).selected()) {
				throw new AssertionError("Expected the override box to start unticked");
			}
			if (screen.widgetOf(CheckoutScreen.SHOW_MODIFIED) == null) {
				throw new AssertionError("Expected Show modified blocks while a block is modified");
			}
			if (screen.widgetOf(CheckoutScreen.CHECKOUT).active) {
				throw new AssertionError("Expected Check out to be greyed out before a version is picked");
			}
		});
		moveAway(context);
		context.waitTicks(2);
		context.takeScreenshot("mcvcs-builds-overlay-checkout");

		// Picked, v1 still cannot be checked out over the modified block until the box is ticked.
		clickCheckoutVersion(context, 1);
		context.runOnClient(client -> {
			var button = ((CheckoutScreen) client.screen).widgetOf(CheckoutScreen.CHECKOUT);
			if (!button.getMessage().getString().equals(CheckoutScreen.CHECKOUT + " v1") || button.active) {
				throw new AssertionError("Expected a greyed-out 'Check out v1' but got '" + button.getMessage().getString() + "', active " + button.active);
			}
		});
		moveAway(context);
		context.waitTicks(2);
		context.takeScreenshot("mcvcs-builds-overlay-checkout-picked");

		// Show modified blocks closes everything and highlights the change.
		clickCheckoutWidget(context, CheckoutScreen.SHOW_MODIFIED);
		context.waitFor(client -> client.screen == null);
		context.waitFor(client -> DiffManager.active() != null);

		// Ticked, v1 goes into the placement over the modified block.
		openCheckout(context);
		clickCheckoutVersion(context, 1);
		clickCheckoutWidget(context, CheckoutScreen.OVERRIDE);
		context.waitFor(client -> ((CheckoutScreen) client.screen).widgetOf(CheckoutScreen.CHECKOUT).active);
		clickCheckoutWidget(context, CheckoutScreen.CHECKOUT);
		context.waitFor(client -> client.screen == null);
		context.waitFor(client -> ClientPlacements.selected() != null && ClientPlacements.selected().head() == 1);
		if (!blockAt(singleplayer, towerMin).is(Blocks.STONE_BRICKS) || blockAt(singleplayer, towerMax).is(Blocks.GOLD_BLOCK)) {
			throw new AssertionError("Expected v1 in " + label + " but found " + blockAt(singleplayer, towerMin) + " and " + blockAt(singleplayer, towerMax));
		}

		// Nothing modified now: no Show modified blocks, and v3 goes back without ticking the box.
		openCheckout(context);
		context.waitFor(client -> ((CheckoutScreen) client.screen).modified() == 0);
		if (context.computeOnClient(client -> ((CheckoutScreen) client.screen).widgetOf(CheckoutScreen.SHOW_MODIFIED)) != null) {
			throw new AssertionError("Expected no Show modified blocks with nothing modified");
		}
		clickCheckoutVersion(context, 3);
		clickCheckoutWidget(context, CheckoutScreen.CHECKOUT);
		context.waitFor(client -> client.screen == null);
		context.waitFor(client -> ClientPlacements.selected() != null && ClientPlacements.selected().head() == 3);
	}

	/** Opens the overlay and its checkout window. */
	private static void openCheckout(ClientGameTestContext context) {
		open(context);
		context.waitFor(client -> headerButton(client, BuildBrowserScreen.CHECKOUT).active);
		clickHeader(context, BuildBrowserScreen.CHECKOUT);
		context.waitForScreen(CheckoutScreen.class);
	}

	/** Clicks the cell of version {@code version} in the checkout window. */
	private static void clickCheckoutVersion(ClientGameTestContext context, int version) {
		ScreenRectangle cell = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof CheckoutScreen screen ? screen.cellOf(version) : null;
			if (found == null) {
				throw new AssertionError("Expected a cell for v" + version + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, cell.left() + cell.width() / 2.0, cell.top() + cell.height() / 2.0);
	}

	/** Clicks the button or checkbox labelled {@code label} in the checkout window. */
	private static void clickCheckoutWidget(ClientGameTestContext context, String label) {
		ScreenRectangle widget = context.computeOnClient(client -> {
			ScreenRectangle found = client.screen instanceof CheckoutScreen screen ? screen.rectangleOf(label) : null;
			if (found == null) {
				throw new AssertionError("Expected " + label + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, widget.left() + widget.width() / 2.0, widget.top() + widget.height() / 2.0);
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
