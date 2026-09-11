package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.deleteSchematics;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.nio.file.Files;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.selection.SelectionBoxRenderer;
import tony.mcvcs.project.ProjectBox;
import tony.mcvcs.project.SelectedProject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** {@code /vcs select} switches the selected project, so the client draws its box and later commits go to it. */
@SuppressWarnings("UnstableApiUsage")
public class VcsSelectCommandGameTest implements FabricClientGameTest {
	private static final String FIRST = "gametest-select-first";
	private static final String SECOND = "gametest-select-second";
	private static final String FIRST_V2 = FIRST + "_v2";
	private static final String SECOND_V2 = SECOND + "_v2";

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// WorldEdit only knows its schematics directory once a server platform is up.
			deleteSchematics(FIRST, SECOND, FIRST_V2, SECOND_V2);

			// Two 3x2x2 stone boxes: the first in front of and to the right of the player, the second to the left.
			BlockPos firstMin = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos firstMax = firstMin.offset(2, 1, 1);
			BlockPos secondMin = playerPos(singleplayer).offset(-4, 0, 2);
			BlockPos secondMax = secondMin.offset(2, 1, 1);
			ProjectBox firstBox = new ProjectBox(firstMin, firstMax);
			ProjectBox secondBox = new ProjectBox(secondMin, secondMax);

			fillBox(singleplayer, firstMin, firstMax, Blocks.STONE.defaultBlockState(), firstMin, Blocks.STONE.defaultBlockState());
			fillBox(singleplayer, secondMin, secondMax, Blocks.STONE.defaultBlockState(), secondMin, Blocks.STONE.defaultBlockState());

			select(singleplayer, firstMin, firstMax);
			runCommand(context, "vcs create " + FIRST);
			read(schematic(FIRST));
			select(singleplayer, secondMin, secondMax);
			runCommand(context, "vcs create " + SECOND);
			read(schematic(SECOND));
			// Creating selects, so the second build is the one shown now.
			assertSelected(waitForSelection(context, SECOND), SECOND, 1, secondBox);

			// A name that was never created leaves the selection alone.
			runCommand(context, "vcs select gametest-select-missing");
			context.waitTicks(5);
			assertSelected(context.computeOnClient(client -> SelectionBoxRenderer.selected()), SECOND, 1, secondBox);

			runCommand(context, "vcs select " + FIRST);
			assertSelected(waitForSelection(context, FIRST), FIRST, 1, firstBox);

			lookAt(context, firstMin, firstMax);
			screenshotLastFrame(context, "mcvcs-vcs-select");

			// Commit follows the selection: the first build gets a v2, the second does not.
			runCommand(context, "vcs commit");
			read(schematic(FIRST_V2));
			if (Files.exists(schematic(SECOND_V2))) {
				throw new AssertionError("Commit after /vcs select must not write " + schematic(SECOND_V2));
			}
			assertSelected(waitForSelection(context, FIRST, 2), FIRST, 2, firstBox);

			// Selecting the second build again shows its latest state, still v1.
			runCommand(context, "vcs select " + SECOND);
			assertSelected(waitForSelection(context, SECOND), SECOND, 1, secondBox);
		}
	}

	private static SelectedProject waitForSelection(ClientGameTestContext context, String name) {
		return waitForSelection(context, name, 1);
	}

	private static SelectedProject waitForSelection(ClientGameTestContext context, String name, int version) {
		context.waitFor(client -> {
			SelectedProject selected = SelectionBoxRenderer.selected();
			return selected != null && selected.name().equals(name) && selected.version() == version;
		});
		return context.computeOnClient(client -> SelectionBoxRenderer.selected());
	}

	private static void assertSelected(SelectedProject selected, String name, int version, ProjectBox box) {
		if (selected == null) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but nothing is selected");
		}
		if (!selected.name().equals(name) || selected.version() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but got '" + selected.name() + "' v" + selected.version());
		}
		if (!selected.box().equals(box)) {
			throw new AssertionError("Expected selection box " + box + " but got " + selected.box());
		}
	}
}
