package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.deleteSchematics;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.preview.ClientPreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.preview.PreviewBox;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsPreviewCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-preview";
	private static final String V2 = BUILD_NAME + "_v2";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// WorldEdit only knows its schematics directory once a server platform is up.
			deleteSchematics(BUILD_NAME, V2);

			// v1: a 3x2x2 stone box in front of and to the right of the player, with a gold block in the top north-west
			// corner and the top north-east corner left empty; both corners face the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BlockPos gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			BlockPos hole = new BlockPos(max.getX(), max.getY(), min.getZ());
			PreviewBox box = new PreviewBox(min, max);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, hole, Blocks.AIR.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME));

			// v2: the whole box rebuilt out of diamond, hole included, so a preview of v1 differs from the world both
			// where it has a block and where it has none.
			fillBox(singleplayer, min, max, Blocks.DIAMOND_BLOCK.defaultBlockState(), gold, Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(V2));

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

	/** Turns the player toward the box so the screenshots show it. */
	private static void lookAt(ClientGameTestContext context, BlockPos min, BlockPos max) {
		context.runOnClient(client -> {
			double x = (min.getX() + max.getX() + 1) / 2.0 - client.player.getX();
			double y = (min.getY() + max.getY() + 1) / 2.0 - client.player.getEyeY();
			double z = (min.getZ() + max.getZ() + 1) / 2.0 - client.player.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
			float pitch = (float) Math.toDegrees(-Math.atan2(y, Math.sqrt(x * x + z * z)));
			client.player.setYRot(yaw);
			client.player.setXRot(pitch);
		});
		context.waitTicks(2);
	}

	private static ClientPreview waitForPreview(ClientGameTestContext context, int version) {
		context.waitFor(client -> {
			ClientPreview preview = PreviewManager.active();
			return preview != null && preview.version() == version;
		});
		return context.computeOnClient(client -> PreviewManager.active());
	}

	private static void assertNoPreview(ClientGameTestContext context) {
		context.waitTicks(5);
		ClientPreview preview = context.computeOnClient(client -> PreviewManager.active());
		if (preview != null) {
			throw new AssertionError("Expected no preview but '" + preview.name() + "' v" + preview.version() + " is shown");
		}
	}

	private static void assertPreview(ClientPreview preview, String name, int version, ResourceKey<Level> dimension, PreviewBox box) {
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
