package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.buildFile;
import static tony.mcvcs.gametest.VcsTestSupport.create;
import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import tony.mcvcs.build.Build;
import tony.mcvcs.migration.Migrations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Schematics an older version of the mod wrote as {@code <buildname>/v<N>.schem} are renamed to
 * {@code <buildname>/<buildname>-v<N>.schem} when the server starts, and a rename that would overwrite a file
 * fails the migration instead. A {@code build.json} from when builds belonged to one world has its placements moved
 * under that world's name.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsMigrationGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-migrate";

	@Override
	protected void run(ClientGameTestContext context) {
		TestWorldSave save;
		String world;
		BlockPos gold;

		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			save = singleplayer.getWorldSave();
			world = singleplayer.getServer().computeOnServer(Build::worldOf);

			// v1 has a gold corner, v2 is all stone, so the two files can be told apart after the rename.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			create(context, singleplayer, BUILD_NAME, min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.STONE.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
		}

		// With the server stopped, give both schematics the names older versions of the mod used, and write build.json
		// the way they did when a build belonged to one world: a world field and that world's placements alone.
		move(schematic(BUILD_NAME, 1), oldName(1));
		move(schematic(BUILD_NAME, 2), oldName(2));
		JsonObject shared = readJson(buildFile(BUILD_NAME));
		JsonObject placements = shared.getAsJsonObject("placements").getAsJsonObject(world);
		JsonObject old = new JsonObject();
		old.addProperty("name", BUILD_NAME);
		old.addProperty("world", world);
		old.add("version", shared.get("version"));
		old.add("versions", shared.get("versions"));
		old.add("placements", placements);
		writeJson(buildFile(BUILD_NAME), old);

		// Starting the server migrates them back before anything else reads the folder.
		try (TestSingleplayerContext reopened = save.open()) {
			reopened.getClientLevel().waitForChunksRender();
			for (int version = 1; version <= 2; version++) {
				if (Files.exists(oldName(version))) {
					throw new AssertionError("Expected " + oldName(version) + " to be renamed on server start");
				}
			}
			// The placements are now this world's in the shared layout, and the world field is gone.
			JsonObject migrated = readJson(buildFile(BUILD_NAME));
			if (migrated.has("world") || !placements.equals(migrated.getAsJsonObject("placements").getAsJsonObject(world))
				|| migrated.getAsJsonObject("placements").size() != 1) {
				throw new AssertionError("Expected the placements under world '" + world + "' after migration but got " + migrated);
			}
			assertBlock(read(schematic(BUILD_NAME, 1)), gold, Blocks.GOLD_BLOCK);
			assertBlock(read(schematic(BUILD_NAME, 2)), gold, Blocks.STONE);

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
			assertBlock(read(schematic(BUILD_NAME, 1)), gold, Blocks.GOLD_BLOCK);
			assertBlock(read(oldName(1)), gold, Blocks.STONE);
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

	private static JsonObject readJson(Path file) {
		try {
			return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		} catch (IOException e) {
			throw new AssertionError("Could not read " + file, e);
		}
	}

	private static void writeJson(Path file, JsonObject json) {
		try {
			Files.writeString(file, json.toString());
		} catch (IOException e) {
			throw new AssertionError("Could not write " + file, e);
		}
	}

	private static void move(Path from, Path to) {
		try {
			Files.move(from, to);
		} catch (IOException e) {
			throw new AssertionError("Could not rename " + from + " to " + to, e);
		}
	}
}
