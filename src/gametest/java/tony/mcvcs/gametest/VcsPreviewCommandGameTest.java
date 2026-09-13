package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.resetProjects;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;
import static tony.mcvcs.gametest.VcsTestSupport.suggestions;

import java.util.HashSet;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.preview.ClientPreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.project.ProjectBox;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsPreviewCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-preview";

	@Override
	public void runTest(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetProjects(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// v1: a 3x2x2 stone box in front of and to the right of the player, with a gold block in the top north-west
			// corner and the top north-east corner left empty; both corners face the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BlockPos gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			BlockPos hole = new BlockPos(max.getX(), max.getY(), min.getZ());
			ProjectBox box = new ProjectBox(min, max);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, hole, Blocks.AIR.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));

			// v2: the whole box rebuilt out of diamond, hole included, so a preview of v1 differs from the world both
			// where it has a block and where it has none.
			fillBox(singleplayer, min, max, Blocks.DIAMOND_BLOCK.defaultBlockState(), gold, Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));

			// Tab completion offers every version of the selected build alongside "off".
			assertSuggestions(singleplayer, "vcs preview ", List.of("1", "2", "off"));

			// Nothing to preview beyond the latest version.
			runCommand(context, "vcs preview 3");
			assertNoPreview(context);

			runCommand(context, "vcs preview 1");
			ClientPreview preview = waitForPreview(context, 1);
			assertPreview(preview, BUILD_NAME, 1, Level.OVERWORLD, box);
			assertPreviewBlock(preview, gold, Blocks.GOLD_BLOCK);
			assertPreviewBlock(preview, min, Blocks.STONE);
			// Air in the preview hides the real block there rather than falling through to it.
			assertPreviewBlock(preview, hole, Blocks.AIR);
			// The real world still holds v2; only the client's picture of it changed.
			assertWorldBlock(singleplayer, gold, Blocks.DIAMOND_BLOCK);
			assertWorldBlock(singleplayer, hole, Blocks.DIAMOND_BLOCK);

			lookAt(context, min, max);
			singleplayer.getClientLevel().waitForChunksRender();
			context.takeScreenshot("mcvcs-vcs-preview-v1");

			runCommand(context, "vcs preview 2");
			preview = waitForPreview(context, 2);
			assertPreviewBlock(preview, gold, Blocks.DIAMOND_BLOCK);
			assertPreviewBlock(preview, hole, Blocks.DIAMOND_BLOCK);
			singleplayer.getClientLevel().waitForChunksRender();
			context.takeScreenshot("mcvcs-vcs-preview-v2");

			runCommand(context, "vcs preview off");
			assertNoPreview(context);
			singleplayer.getClientLevel().waitForChunksRender();
			context.takeScreenshot("mcvcs-vcs-preview-off");
		}
	}

	private static ClientPreview waitForPreview(ClientGameTestContext context, int version) {
		context.waitFor(client -> {
			ClientPreview preview = PreviewManager.active();
			return preview != null && preview.version() == version;
		});
		return context.computeOnClient(client -> PreviewManager.active());
	}

	private static void assertSuggestions(TestSingleplayerContext singleplayer, String command, List<String> expected) {
		List<String> actual = suggestions(singleplayer, command);
		if (!new HashSet<>(actual).equals(new HashSet<>(expected))) {
			throw new AssertionError("Expected /" + command + " to suggest " + expected + " but got " + actual);
		}
	}

	private static void assertNoPreview(ClientGameTestContext context) {
		context.waitTicks(5);
		ClientPreview preview = context.computeOnClient(client -> PreviewManager.active());
		if (preview != null) {
			throw new AssertionError("Expected no preview but '" + preview.name() + "' v" + preview.version() + " is shown");
		}
	}

	private static void assertPreview(ClientPreview preview, String name, int version, ResourceKey<Level> dimension, ProjectBox box) {
		if (!preview.name().equals(name) || preview.version() != version) {
			throw new AssertionError("Expected preview of '" + name + "' v" + version + " but got '" + preview.name() + "' v" + preview.version());
		}
		if (!preview.dimension().equals(dimension)) {
			throw new AssertionError("Expected preview in " + dimension + " but got " + preview.dimension());
		}
		if (!preview.box().equals(box)) {
			throw new AssertionError("Expected preview box " + box + " but got " + preview.box());
		}
	}

	private static void assertPreviewBlock(ClientPreview preview, BlockPos pos, Block expected) {
		Block actual = preview.stateAt(pos).getBlock();
		if (actual != expected) {
			throw new AssertionError("Expected preview to show " + expected + " at " + pos + " but it shows " + actual);
		}
	}

	private static void assertWorldBlock(TestSingleplayerContext singleplayer, BlockPos pos, Block expected) {
		Block actual = singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(pos).getBlock());
		if (actual != expected) {
			throw new AssertionError("Expected the world to hold " + expected + " at " + pos + " but it holds " + actual);
		}
	}
}
