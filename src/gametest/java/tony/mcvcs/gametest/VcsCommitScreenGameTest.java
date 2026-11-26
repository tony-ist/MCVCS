package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.create;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import tony.mcvcs.client.browser.BuildBrowser;
import tony.mcvcs.client.browser.BuildBrowserKey;
import tony.mcvcs.client.browser.BuildBrowserScreen;
import tony.mcvcs.client.browser.CommitPreview;
import tony.mcvcs.client.browser.CommitScreen;
import tony.mcvcs.client.browser.Thumbnail;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.network.CommitPreviewPayload;

/**
 * The builds overlay's Commit button, left of Checkout, opens a window previewing the selected placement as it would
 * be committed: with nothing changed it says so and greys out Commit and Show modified blocks; with a block added,
 * one removed and one changed it counts each and marks them in the preview; Show modified blocks runs
 * {@code /vcs diff}; the overlay's Diff button, left of Commit, counts the changes and toggles their highlighting; Commit with a tag typed commits the placement as the next version with that tag.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsCommitScreenGameTest extends VcsGameTest {
	private static final String NAME = "gametest-commit-tower";

	@Override
	protected void run(ClientGameTestContext context) {
		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			ClientConfig.override(new ClientConfig.Settings(0.0, ClientConfig.DEFAULT_CELL_SIZE, 40, true, true));

			// A 3x3x3 block of stone bricks with its top corner left empty.
			BlockPos min = playerPos(singleplayer).offset(4, 3, 4);
			BlockPos max = min.offset(2, 2, 2);
			fillBox(singleplayer, min, max, Blocks.STONE_BRICKS.defaultBlockState(), max, Blocks.AIR.defaultBlockState());
			create(context, singleplayer, NAME, min, max);
			read(schematic(NAME, 1));

			// Nothing changed: Commit and Show modified blocks are greyed out.
			openCommit(context);
			waitForSummary(context);
			context.runOnClient(client -> {
				CommitPreviewPayload summary = screen(client).summary();
				if (summary.total() != 0) {
					throw new AssertionError("Expected nothing changed but got " + summary);
				}
				if (screen(client).widgetOf(CommitScreen.COMMIT).active || screen(client).widgetOf(CommitScreen.SHOW_MODIFIED).active) {
					throw new AssertionError("Expected Commit and Show modified blocks greyed out with nothing changed");
				}
			});
			waitForPreview(context);
			moveTo(context, 1, 1);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-commit-window-unchanged");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.commitPreview() == null);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);

			// One block added, one removed, one changed: each counted and marked.
			setBlock(singleplayer, max, Blocks.STONE_BRICKS.defaultBlockState());
			setBlock(singleplayer, min.offset(2, 2, 0), Blocks.AIR.defaultBlockState());
			setBlock(singleplayer, min.offset(0, 2, 2), Blocks.COBBLESTONE.defaultBlockState());

			// The Diff button, left of Commit, counts them and toggles their highlighting in the world.
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> headerButton(client, BuildBrowserScreen.DIFF + " (3)") != null);
			context.runOnClient(client -> {
				Button diff = headerButton(client, BuildBrowserScreen.DIFF + " (3)");
				if (!diff.active || diff.getRight() > headerButton(client, BuildBrowserScreen.COMMIT).getX()) {
					throw new AssertionError("Expected Diff (3) pressable and left of Commit");
				}
			});
			clickHeader(context, BuildBrowserScreen.DIFF + " (3)");
			context.waitFor(client -> DiffManager.active() != null && headerButton(client, BuildBrowserScreen.HIDE_DIFF) != null);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-browser-hide-diff");
			clickHeader(context, BuildBrowserScreen.HIDE_DIFF);
			context.waitFor(client -> DiffManager.active() == null && headerButton(client, BuildBrowserScreen.DIFF + " (3)") != null);
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);

			openCommit(context);
			waitForSummary(context);
			context.runOnClient(client -> {
				CommitPreviewPayload summary = screen(client).summary();
				if (summary.added() != 1 || summary.removed() != 1 || summary.changed() != 1) {
					throw new AssertionError("Expected 1 added, 1 removed and 1 changed but got " + summary);
				}
				if (!screen(client).widgetOf(CommitScreen.COMMIT).active || !screen(client).widgetOf(CommitScreen.SHOW_MODIFIED).active) {
					throw new AssertionError("Expected Commit and Show modified blocks pressable with changes");
				}
			});
			waitForPreview(context);
			moveTo(context, 1, 1);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-commit-window");

			// Show modified blocks closes everything and highlights the changes in the world.
			clickWidget(context, CommitScreen.SHOW_MODIFIED);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> DiffManager.active() != null);

			// The tag typed goes with the commit.
			openCommit(context);
			waitForSummary(context);
			clickWidget(context, "Tag");
			context.getInput().typeChars("2.0");
			context.waitTicks(2);
			if (!context.computeOnClient(client -> screen(client).widgetOf(CommitScreen.COMMIT).active)) {
				throw new AssertionError("Expected Commit pressable with a new tag");
			}
			clickWidget(context, CommitScreen.COMMIT);
			context.waitFor(client -> client.screen == null);
			context.waitFor(client -> ClientPlacements.selected() != null && ClientPlacements.selected().head() == 2);
			if (!Files.exists(schematic(NAME, 2))) {
				throw new AssertionError("Expected v2 of " + NAME + " to be saved");
			}
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.builds().stream().anyMatch(build -> build.name().equals(NAME) && build.version() == 2 && build.latest().tags().contains("2.0")));
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitFor(client -> client.screen == null);
		} finally {
			ClientConfig.load();
		}
	}

	private static CommitScreen screen(Minecraft client) {
		return (CommitScreen) client.screen;
	}

	/** Opens the overlay and its commit window. */
	private static void openCommit(ClientGameTestContext context) {
		context.getInput().pressKey(BuildBrowserKey.KEY);
		context.waitForScreen(BuildBrowserScreen.class);
		context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.LOADED);
		context.waitFor(client -> headerButton(client, BuildBrowserScreen.COMMIT) != null && headerButton(client, BuildBrowserScreen.COMMIT).active);
		context.runOnClient(client -> {
			Button commit = headerButton(client, BuildBrowserScreen.COMMIT);
			Button checkout = headerButton(client, BuildBrowserScreen.CHECKOUT);
			if (commit.getRight() > checkout.getX()) {
				throw new AssertionError("Expected Commit left of Checkout");
			}
		});
		Button button = context.computeOnClient(client -> headerButton(client, BuildBrowserScreen.COMMIT));
		clickAt(context, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
		context.waitForScreen(CommitScreen.class);
	}

	private static void waitForSummary(ClientGameTestContext context) {
		context.waitFor(client -> client.screen instanceof CommitScreen screen && screen.summary() != null);
	}

	private static void waitForPreview(ClientGameTestContext context) {
		context.waitFor(client -> {
			CommitPreview preview = BuildBrowser.commitPreview();
			Thumbnail thumbnail = preview == null ? null : preview.thumbnail();
			if (thumbnail != null && thumbnail.state() == Thumbnail.State.FAILED) {
				throw new AssertionError("Commit preview failed: " + thumbnail.failure());
			}
			return thumbnail != null && thumbnail.state() == Thumbnail.State.READY;
		});
	}

	private static Button headerButton(Minecraft client, String label) {
		return client.screen instanceof BuildBrowserScreen screen ? screen.children().stream()
			.filter(child -> child instanceof Button found && found.getMessage().getString().equals(label))
			.map(Button.class::cast).findFirst().orElse(null) : null;
	}

	private static void clickHeader(ClientGameTestContext context, String label) {
		Button button = context.computeOnClient(client -> headerButton(client, label));
		clickAt(context, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
	}

	private static void clickWidget(ClientGameTestContext context, String label) {
		AbstractWidget widget = context.computeOnClient(client -> {
			AbstractWidget found = client.screen instanceof CommitScreen screen ? screen.widgetOf(label) : null;
			if (found == null) {
				throw new AssertionError("Expected " + label + " on " + client.screen);
			}
			return found;
		});
		clickAt(context, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
	}

	private static void clickAt(ClientGameTestContext context, double x, double y) {
		moveTo(context, x, y);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void moveTo(ClientGameTestContext context, double x, double y) {
		double scale = context.computeOnClient(client -> {
			Window window = client.getWindow();
			return (double) window.getScreenWidth() / window.getGuiScaledWidth();
		});
		context.getInput().setCursorPos(x * scale, y * scale);
		context.waitTick();
	}
}
