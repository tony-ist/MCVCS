package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.nio.file.Files;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.build.BuildRegistry;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * The {@code mcvcs/} folder is shared by every world in the game directory, but each build records the world it
 * was created in and only that world sees it: another world lists nothing, cannot select it, does not show its
 * box and cannot reuse its name.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsWorldScopeGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-scope";

	@Override
	public void runTest(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext first = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			first.getClientLevel().waitForChunksRender();

			BlockPos min = playerPos(first).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(first, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			select(first, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			context.waitFor(client -> ClientBuilds.selected() != null);
		}

		// A different world in the same game directory, so the same mcvcs/ folder.
		try (TestSingleplayerContext second = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			second.getClientLevel().waitForChunksRender();

			List<String> names = second.getServer().computeOnServer(server -> BuildRegistry.names(server));
			if (!names.isEmpty()) {
				throw new AssertionError("Expected no builds in a different world but got " + names);
			}

			// The first world's selection is not this world's, so nothing is drawn.
			context.waitTicks(5);
			if (context.computeOnClient(client -> ClientBuilds.selected()) != null) {
				throw new AssertionError("Expected no selected build in a different world");
			}

			runCommand(context, "vcs select " + BUILD_NAME);
			context.waitTicks(5);
			if (context.computeOnClient(client -> ClientBuilds.selected()) != null) {
				throw new AssertionError("Selecting another world's build must fail");
			}

			// Nothing selected here, so there is nothing to commit either.
			runCommand(context, "vcs commit");
			if (Files.exists(schematic(BUILD_NAME, 2))) {
				throw new AssertionError("Commit in a different world must not write " + schematic(BUILD_NAME, 2));
			}

			// The name is taken by the first world's build, so this world cannot claim its folder.
			BlockPos min = playerPos(second).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(second, min, max, Blocks.DIAMOND_BLOCK.defaultBlockState(), min, Blocks.DIAMOND_BLOCK.defaultBlockState());
			select(second, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			context.waitTicks(5);
			if (context.computeOnClient(client -> ClientBuilds.selected()) != null) {
				throw new AssertionError("Creating a build with another world's name must fail");
			}
			// A successful create would have overwritten v1 with this world's diamond box.
			Clipboard v1 = read(schematic(BUILD_NAME, 1));
			assertBlock(v1, v1.getMinimumPoint(), BlockTypes.STONE);
		}
	}
}
