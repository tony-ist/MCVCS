package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.deleteSchematics;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

import tony.mcvcs.client.selection.SelectionBoxRenderer;
import tony.mcvcs.project.Project;
import tony.mcvcs.project.ProjectBox;
import tony.mcvcs.project.ProjectRegistry;
import tony.mcvcs.project.SelectedProject;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * Projects and the player's selection are saved with the world: after leaving and reopening it, the selected
 * project is shown again at its latest version and the next commit still covers the box captured at create time.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsReloadGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-reload";
	private static final String OTHER = "gametest-reload-other";
	private static final String V2 = BUILD_NAME + "_v2";
	private static final String V3 = BUILD_NAME + "_v3";

	@Override
	public void runTest(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();
		TestWorldSave save;
		BlockPos min;
		BlockPos max;
		BlockPos gold;

		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// WorldEdit only knows its schematics directory once a server platform is up.
			deleteSchematics(BUILD_NAME, V2, V3, OTHER);
			save = singleplayer.getWorldSave();

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
			read(schematic(OTHER));

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME));
			runCommand(context, "vcs commit");
			read(schematic(V2));
			assertSelected(waitForSelection(context, BUILD_NAME, 2), BUILD_NAME, 2, new ProjectBox(min, max));
		}

		// Closing the world saves it. Reopening starts a fresh server, so anything not on disk is gone.
		try (TestSingleplayerContext reopened = save.open()) {
			reopened.getClientLevel().waitForChunksRender();

			// The server knows both projects again and remembers which one the player had selected.
			List<String> names = reopened.getServer().computeOnServer(server -> ProjectRegistry.names(server.overworld()));
			if (!names.equals(List.of(BUILD_NAME, OTHER))) {
				throw new AssertionError("Expected projects " + List.of(BUILD_NAME, OTHER) + " after reload but got " + names);
			}
			Optional<Project> selected = reopened.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				return ProjectRegistry.selected(player);
			});
			if (selected.isEmpty() || !selected.get().equals(new Project(BUILD_NAME, new ProjectBox(min, max), 2))) {
				throw new AssertionError("Expected '" + BUILD_NAME + "' v2 at " + new ProjectBox(min, max) + " selected after reload but got " + selected);
			}

			// The client is told on join, so the box is drawn without running any command.
			assertSelected(waitForSelection(context, BUILD_NAME, 2), BUILD_NAME, 2, new ProjectBox(min, max));
			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-reload");

			// Commit picks up where it left off, with the box from create time: the gold block is still in the corner.
			runCommand(context, "vcs commit");
			Clipboard v3 = read(schematic(V3));
			assertSize(v3, BlockVector3.at(3, 2, 2));
			assertBlock(v3, adapter.adapt(gold), BlockTypes.GOLD_BLOCK);
			assertSelected(waitForSelection(context, BUILD_NAME, 3), BUILD_NAME, 3, new ProjectBox(min, max));
		}
	}

	private static SelectedProject waitForSelection(ClientGameTestContext context, String name, int version) {
		context.waitFor(client -> {
			SelectedProject selected = SelectionBoxRenderer.selected();
			return selected != null && selected.name().equals(name) && selected.version() == version;
		});
		return context.computeOnClient(client -> SelectionBoxRenderer.selected());
	}

	private static void assertSelected(SelectedProject selected, String name, int version, ProjectBox box) {
		if (!selected.name().equals(name) || selected.version() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but got '" + selected.name() + "' v" + selected.version());
		}
		if (!selected.box().equals(box)) {
			throw new AssertionError("Expected selection box " + box + " but got " + selected.box());
		}
	}
}
