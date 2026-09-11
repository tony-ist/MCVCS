package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertOrigin;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.deleteSchematics;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsCommitCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-commit";
	private static final String V2 = BUILD_NAME + "_v2";
	private static final String V3 = BUILD_NAME + "_v3";

	@Override
	public void runTest(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// WorldEdit only knows its schematics directory once a server platform is up.
			deleteSchematics(BUILD_NAME, V2, V3);

			// Same 3x2x2 box as the create test: stone with a gold block at the top north-west corner.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BlockVector3 originCorner = BlockVector3.at(adapter.adapt(min).x(), adapter.adapt(max).y(), adapter.adapt(min).z());
			BlockVector3 expectedOrigin = originCorner.add(0, 1, 0);
			BlockVector3 opposite = adapter.adapt(min).add(adapter.adapt(max)).subtract(originCorner);
			BlockPos gold = adapter.toBlockPos(originCorner);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);

			// Nothing to commit before a build has been created.
			runCommand(context, "vcs commit");
			if (Files.exists(schematic(V2))) {
				throw new AssertionError("Commit without a selected build must not write " + schematic(V2));
			}

			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME));

			// Move the WorldEdit selection well away from the box and edit the box itself. Commit must follow the
			// region captured at create time, so it sees the edit and ignores the new selection.
			select(singleplayer, min.offset(10, 0, 10), max.offset(10, 0, 10));
			setBlock(singleplayer, adapter.toBlockPos(opposite), Blocks.DIAMOND_BLOCK.defaultBlockState());

			runCommand(context, "vcs commit");
			Clipboard v2 = read(schematic(V2));
			assertOrigin(v2, expectedOrigin);
			assertSize(v2, BlockVector3.at(3, 2, 2));
			assertBlock(v2, originCorner, BlockTypes.GOLD_BLOCK);
			assertBlock(v2, opposite, BlockTypes.DIAMOND_BLOCK);

			// Each further commit bumps the version.
			runCommand(context, "vcs commit");
			Clipboard v3 = read(schematic(V3));
			assertOrigin(v3, expectedOrigin);
			assertBlock(v3, opposite, BlockTypes.DIAMOND_BLOCK);

			context.takeScreenshot("mcvcs-vcs-commit");
		}
	}
}
