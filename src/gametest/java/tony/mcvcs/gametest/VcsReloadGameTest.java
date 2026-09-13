package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.resetProjects;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.projectFile;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import tony.mcvcs.client.project.ClientProjects;
import tony.mcvcs.project.Project;
import tony.mcvcs.project.ProjectBox;
import tony.mcvcs.project.ProjectRegistry;
import tony.mcvcs.project.ClientProject;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Projects and the player's selection are saved in the {@code mcvcs/} folder: after leaving and reopening the
 * world, which starts a fresh server, the selected project is shown again at its latest version and the next
 * commit still covers the box captured at create time.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsReloadGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-reload";
	private static final String OTHER = "gametest-reload-other";

	@Override
	public void runTest(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();
		TestWorldSave save;
		String world;
		BlockPos min;
		BlockPos max;
		BlockPos gold;

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetProjects(BUILD_NAME, OTHER);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			save = singleplayer.getWorldSave();
			// The save folder's name is what the project file records as the world.
			world = singleplayer.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());

			// A 3x2x2 stone box with a gold block at its top north-west corner, and a second build to the left so
			// the reload has to restore more than one project and pick the right selection.
			min = playerPos(singleplayer).offset(2, 0, 2);
			max = min.offset(2, 1, 1);
			gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			BlockPos otherMin = playerPos(singleplayer).offset(-4, 0, 2);
			BlockPos otherMax = otherMin.offset(2, 1, 1);

			fillBox(singleplayer, otherMin, otherMax, Blocks.STONE.defaultBlockState(), otherMin, Blocks.STONE.defaultBlockState());
			select(singleplayer, otherMin, otherMax);
			runCommand(context, "vcs create " + OTHER);
			read(schematic(OTHER, 1));

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
			assertSelected(waitForSelection(context, BUILD_NAME, 2), BUILD_NAME, 2, new ProjectBox(min, max));

			// The project's folder describes it: the file is the only thing the reload below can go by.
			assertProjectFile(BUILD_NAME, world, min, max, 2);
		}

		// Closing the world saves it. Reopening starts a fresh server, so anything not on disk is gone.
		try (TestSingleplayerContext reopened = save.open()) {
			reopened.getClientLevel().waitForChunksRender();

			// The server knows both projects again, and only those: other tests' projects belong to other worlds. It
			// also remembers which one the player had selected.
			List<String> names = reopened.getServer().computeOnServer(server -> ProjectRegistry.names(server));
			if (!names.equals(List.of(BUILD_NAME, OTHER))) {
				throw new AssertionError("Expected projects " + List.of(BUILD_NAME, OTHER) + " after reload but got " + names);
			}
			Optional<Project> selected = reopened.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				return ProjectRegistry.selected(player);
			});
			if (selected.isEmpty() || !selected.get().equals(new Project(BUILD_NAME, world, Level.OVERWORLD, new ProjectBox(min, max), 2))) {
				throw new AssertionError("Expected '" + BUILD_NAME + "' v2 at " + new ProjectBox(min, max) + " selected after reload but got " + selected);
			}

			// The client is told on join, so the box is drawn without running any command.
			assertSelected(waitForSelection(context, BUILD_NAME, 2), BUILD_NAME, 2, new ProjectBox(min, max));
			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-reload");

			// Commit picks up where it left off, with the box from create time: the gold block is still in the corner.
			runCommand(context, "vcs commit");
			Clipboard v3 = read(schematic(BUILD_NAME, 3));
			assertSize(v3, BlockVector3.at(3, 2, 2));
			assertBlock(v3, adapter.adapt(gold), BlockTypes.GOLD_BLOCK);
			assertSelected(waitForSelection(context, BUILD_NAME, 3), BUILD_NAME, 3, new ProjectBox(min, max));
		}
	}

	/** Checks the JSON {@code /vcs} wrote for the build against the layout documented in the README. */
	private static void assertProjectFile(String name, String world, BlockPos min, BlockPos max, int version) {
		String json;
		try {
			json = Files.readString(projectFile(name));
		} catch (IOException e) {
			throw new AssertionError("Expected a project file at " + projectFile(name), e);
		}
		JsonObject project = JsonParser.parseString(json).getAsJsonObject();
		if (!project.get("name").getAsString().equals(name)
			|| !project.get("world").getAsString().equals(world)
			|| !project.get("dimension").getAsString().equals("minecraft:overworld")
			|| project.get("version").getAsInt() != version
			|| !blockPos(project.getAsJsonObject("box").get("min")).equals(min)
			|| !blockPos(project.getAsJsonObject("box").get("max")).equals(max)) {
			throw new AssertionError("Expected '" + name + "' v" + version + " in the overworld of '" + world + "' at " + new ProjectBox(min, max) + " but the project file holds " + json);
		}
	}

	private static BlockPos blockPos(JsonElement xyz) {
		JsonArray array = xyz.getAsJsonArray();
		return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
	}

	private static ClientProject waitForSelection(ClientGameTestContext context, String name, int version) {
		context.waitFor(client -> {
			ClientProject selected = ClientProjects.selected();
			return selected != null && selected.name().equals(name) && selected.version() == version;
		});
		return context.computeOnClient(client -> ClientProjects.selected());
	}

	private static void assertSelected(ClientProject selected, String name, int version, ProjectBox box) {
		if (!selected.name().equals(name) || selected.version() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but got '" + selected.name() + "' v" + selected.version());
		}
		if (!selected.box().equals(box)) {
			throw new AssertionError("Expected selection box " + box + " but got " + selected.box());
		}
	}
}
