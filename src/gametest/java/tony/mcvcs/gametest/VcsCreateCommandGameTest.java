package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertOrigin;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.build.BuildStorage;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsCreateCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-build";

	@Override
	public void runTest(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// /vcs requires op, which in singleplayer means cheats must be on.
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 3x2x2 box in front of the player: stone everywhere except one gold block at the corner the origin is anchored to.
			BlockPos playerPos = playerPos(singleplayer);
			BlockPos min = playerPos.offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			// Top north-west corner of the box, spelled out rather than taken from the command's own constants.
			BlockVector3 originCorner = BlockVector3.at(adapter.adapt(min).x(), adapter.adapt(max).y(), adapter.adapt(min).z());
			// The origin sits one block above the build, so a paste lands it below the player instead of around them.
			BlockVector3 expectedOrigin = originCorner.add(0, 1, 0);
			BlockPos gold = adapter.toBlockPos(originCorner);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);

			// The name is a folder name, so one that points outside the builds folder is refused before anything is written.
			runCommand(context, "vcs create ..");
			Path escaped = BuildStorage.root().resolve("..").resolve("v1.schem").normalize();
			if (Files.exists(escaped)) {
				throw new AssertionError("Create with name '..' must not write " + escaped);
			}

			runCommand(context, "vcs create " + BUILD_NAME);

			Clipboard clipboard = read(schematic(BUILD_NAME, 1));
			assertOrigin(clipboard, expectedOrigin);

			// A paste at position `to` puts a clipboard block at `to + (block - origin)`, so check where the build's top
			// layer ends up when pasted at the player's feet: one block below them, never inside them.
			BlockVector3 pasteAt = adapter.adapt(playerPos);
			int pastedTopY = pasteAt.add(originCorner.subtract(clipboard.getOrigin())).y();
			if (pastedTopY != pasteAt.y() - 1) {
				throw new AssertionError("Expected the top layer to paste at y " + (pasteAt.y() - 1) + " but it lands at y " + pastedTopY);
			}

			assertSize(clipboard, BlockVector3.at(3, 2, 2));
			assertBlock(clipboard, originCorner, BlockTypes.GOLD_BLOCK);

			// The diagonally opposite corner must be stone.
			BlockVector3 opposite = adapter.adapt(min).add(adapter.adapt(max)).subtract(originCorner);
			assertBlock(clipboard, opposite, BlockTypes.STONE);

			context.takeScreenshot("mcvcs-vcs-create");
		}
	}
}
