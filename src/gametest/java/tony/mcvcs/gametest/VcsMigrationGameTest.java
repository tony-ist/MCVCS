package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.buildFile;
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
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;

/**
 * A {@code build.json} written before placements existed, with one {@code box} and one {@code head} for the whole
 * build, is converted the first time it is read: its versions keep their schematics, their extents are worked out
 * from them, and the box becomes a placement called {@link Build#MAIN} standing where it stood. A
 * {@code selections.json} from then, which named the selected build alone, is converted the same way: it selects
 * that build's {@link Build#MAIN} placement.
 * <p>
 * This test goes with the conversion itself and can be deleted with it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsMigrationGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-migration";

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A build with two versions, so the conversion has to work out an extent for each of them.
			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(1, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), max, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));

			// Back to how the mod before placements would have left the folder: one box, one head, no versions map.
			String world = singleplayer.getServer().computeOnServer(server ->
				server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
			writeLegacy(world, min, max);

			// Reading it converts it, so the build comes back as one placement holding the version the box held.
			Optional<BuildPlacement> placement = singleplayer.getServer().computeOnServer(server ->
				BuildRegistry.find(server, BUILD_NAME).flatMap(build -> BuildPlacement.of(build, Build.MAIN)));
			BuildBox box = new BuildBox(min, max);
			if (placement.isEmpty() || placement.get().head() != 1 || placement.get().build().version() != 2
				|| !placement.get().dimension().equals(Level.OVERWORLD) || !placement.get().box().equals(box)) {
				throw new AssertionError("Expected '" + BUILD_NAME + "' converted to a " + Build.MAIN + " placement holding v1 at " + box + " but got " + placement.orElse(null));
			}
			// Both versions kept their size, in build space, with version 1's minimum corner as the origin.
			Build build = placement.get().build();
			BuildBox extent = new BuildBox(BlockPos.ZERO, max.subtract(min));
			if (!build.extent(1).equals(extent) || !build.extent(2).equals(extent)) {
				throw new AssertionError("Expected both versions to cover " + extent + " in build space but got " + build.versions());
			}

			// The file was rewritten in the current form, so the conversion happens once and not on every read.
			String json = readBuildFile();
			if (json.contains("\"box\"") || !json.contains("\"placements\"") || !json.contains("\"versions\"")) {
				throw new AssertionError("Expected the build file to have been rewritten with placements but it holds " + json);
			}

			// And the build works from there: it can be selected and committed to as usual.
			runCommand(context, "vcs select " + BUILD_NAME);
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 3));

			// A selection written before placements was the build's name alone, and stands for its main placement.
			writeLegacySelection(world, singleplayer.getServer().computeOnServer(server ->
				server.getPlayerList().getPlayers().get(0).getUUID()));
			Optional<BuildPlacement> selected = singleplayer.getServer().computeOnServer(server ->
				BuildRegistry.selected(server.getPlayerList().getPlayers().get(0)));
			if (selected.isEmpty() || !selected.get().build().name().equals(BUILD_NAME) || !selected.get().name().equals(Build.MAIN)) {
				throw new AssertionError("Expected the selection before placements to become the " + Build.MAIN
					+ " placement of '" + BUILD_NAME + "' but got " + selected.orElse(null));
			}

			// That file was rewritten too, so the conversion happens once and not on every read.
			String selections = readFile(BuildStorage.selectionsFile());
			if (!selections.contains("\"placement\"")) {
				throw new AssertionError("Expected the selections file to have been rewritten with placements but it holds " + selections);
			}
		}
	}

	/** Writes the {@code selections.json} the mod before placements would have written for the build. */
	private static void writeLegacySelection(String world, UUID player) {
		String json = """
			{
			  "%s": {
			    "%s": "%s"
			  }
			}
			""".formatted(world, player, BUILD_NAME);
		try {
			Files.writeString(BuildStorage.selectionsFile(), json);
		} catch (IOException e) {
			throw new AssertionError("Failed to write a selections file before placements at " + BuildStorage.selectionsFile(), e);
		}
	}

	/** Writes the {@code build.json} the mod before placements would have written for the build. */
	private static void writeLegacy(String world, BlockPos min, BlockPos max) {
		String json = """
			{
			  "name": "%s",
			  "world": "%s",
			  "dimension": "minecraft:overworld",
			  "box": {"min": [%d, %d, %d], "max": [%d, %d, %d]},
			  "version": 2,
			  "head": 1
			}
			""".formatted(BUILD_NAME, world, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
		try {
			Files.writeString(buildFile(BUILD_NAME), json);
		} catch (IOException e) {
			throw new AssertionError("Failed to write a build file before placements at " + buildFile(BUILD_NAME), e);
		}
	}

	private static String readBuildFile() {
		return readFile(buildFile(BUILD_NAME));
	}

	private static String readFile(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new AssertionError("Expected a file at " + file, e);
		}
	}
}
