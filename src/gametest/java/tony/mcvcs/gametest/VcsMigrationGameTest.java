package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import tony.mcvcs.migration.Migrations;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Schematics an older version of the mod wrote as {@code <buildname>/v<N>.schem} are renamed to
 * {@code <buildname>/<buildname>-v<N>.schem} when the server starts, and a rename that would overwrite a file
 * fails the migration instead.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsMigrationGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-migrate";

	@Override
	protected void run(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();
		TestWorldSave save;
		BlockPos gold;

		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			save = singleplayer.getWorldSave();

			// v1 has a gold corner, v2 is all stone, so the two files can be told apart after the rename.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.STONE.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
		}

		// With the server stopped, give both schematics the names older versions of the mod used.
		move(schematic(BUILD_NAME, 1), oldName(1));
		move(schematic(BUILD_NAME, 2), oldName(2));

		// Starting the server migrates them back before anything else reads the folder.
		try (TestSingleplayerContext reopened = save.open()) {
			reopened.getClientLevel().waitForChunksRender();
			for (int version = 1; version <= 2; version++) {
				if (Files.exists(oldName(version))) {
					throw new AssertionError("Expected " + oldName(version) + " to be renamed on server start");
				}
			}
			assertBlock(read(schematic(BUILD_NAME, 1)), adapter.adapt(gold), BlockTypes.GOLD_BLOCK);
			assertBlock(read(schematic(BUILD_NAME, 2)), adapter.adapt(gold), BlockTypes.STONE);

			// The renamed files are the ones commands read.
			runCommand(context, "vcs checkout 1");
			BlockPos goldPos = gold;
			boolean restored = reopened.getServer().computeOnServer(server -> server.overworld().getBlockState(goldPos).is(Blocks.GOLD_BLOCK));
			if (!restored) {
				throw new AssertionError("Expected /vcs checkout 1 to restore the gold block from the migrated v1 schematic");
			}

			// An old file whose new name is taken fails the migration and neither file is touched, so nothing is lost.
			try {
				Files.copy(schematic(BUILD_NAME, 2), oldName(1));
			} catch (IOException e) {
				throw new AssertionError("Could not write " + oldName(1), e);
			}
			try {
				Migrations.runAll();
				throw new AssertionError("Expected the migration to fail with both " + oldName(1) + " and " + schematic(BUILD_NAME, 1) + " present");
			} catch (Migrations.MigrationException expected) {
				if (!expected.getMessage().contains(oldName(1).toString())) {
					throw new AssertionError("Expected the failure to name " + oldName(1) + " but got: " + expected.getMessage());
				}
			}
			assertBlock(read(schematic(BUILD_NAME, 1)), adapter.adapt(gold), BlockTypes.GOLD_BLOCK);
			assertBlock(read(oldName(1)), adapter.adapt(gold), BlockTypes.STONE);
			try {
				Files.delete(oldName(1));
			} catch (IOException e) {
				throw new AssertionError("Could not clean up " + oldName(1), e);
			}
		}
	}

	/** Where older versions of the mod wrote version {@code version}, spelled out rather than taken from the mod. */
	private static Path oldName(int version) {
		return schematic(BUILD_NAME, version).resolveSibling("v" + version + ".schem");
	}

	private static void move(Path from, Path to) {
		try {
			Files.move(from, to);
		} catch (IOException e) {
			throw new AssertionError("Could not rename " + from + " to " + to, e);
		}
	}
}
