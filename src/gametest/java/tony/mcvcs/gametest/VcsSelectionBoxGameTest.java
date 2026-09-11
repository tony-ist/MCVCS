package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.resetProjects;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.selection.SelectionBoxRenderer;
import tony.mcvcs.project.ProjectBox;
import tony.mcvcs.project.SelectedProject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** The client learns which project is selected and draws its bounding box. */
@SuppressWarnings("UnstableApiUsage")
public class VcsSelectionBoxGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-selection";

	@Override
	public void runTest(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetProjects(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Nothing is selected yet, so nothing is drawn.
			context.waitTicks(5);
			if (context.computeOnClient(client -> SelectionBoxRenderer.selected()) != null) {
				throw new AssertionError("Expected no selected project before /vcs create");
			}

			// A 3x2x2 stone box in front of and to the right of the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			ProjectBox box = new ProjectBox(min, max);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			assertSelected(waitForSelection(context, 1), BUILD_NAME, 1, box);

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-selection-box");

			// Committing keeps the box and bumps the version the client shows.
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
			assertSelected(waitForSelection(context, 2), BUILD_NAME, 2, box);
		}
	}

	private static SelectedProject waitForSelection(ClientGameTestContext context, int version) {
		context.waitFor(client -> {
			SelectedProject selected = SelectionBoxRenderer.selected();
			return selected != null && selected.version() == version;
		});
		return context.computeOnClient(client -> SelectionBoxRenderer.selected());
	}

	private static void assertSelected(SelectedProject selected, String name, int version, ProjectBox box) {
		if (!selected.name().equals(name) || selected.version() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but got '" + selected.name() + "' v" + selected.version());
		}
		if (!selected.dimension().equals(Level.OVERWORLD)) {
			throw new AssertionError("Expected selection in the overworld but got " + selected.dimension());
		}
		if (!selected.box().equals(box)) {
			throw new AssertionError("Expected selection box " + box + " but got " + selected.box());
		}
	}
}
