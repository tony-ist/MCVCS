package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.nio.file.Files;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** {@code /vcs select} switches the selected build, so the client draws its box and later commits go to it. */
@SuppressWarnings("UnstableApiUsage")
public class VcsSelectCommandGameTest extends VcsGameTest {
	private static final String FIRST = "gametest-select-first";
	private static final String SECOND = "gametest-select-second";

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(FIRST, SECOND);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Two 3x2x2 stone boxes: the first in front of and to the right of the player, the second to the left.
			BlockPos firstMin = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos firstMax = firstMin.offset(2, 1, 1);
			BlockPos secondMin = playerPos(singleplayer).offset(-4, 0, 2);
			BlockPos secondMax = secondMin.offset(2, 1, 1);
			BuildBox firstBox = new BuildBox(firstMin, firstMax);
			BuildBox secondBox = new BuildBox(secondMin, secondMax);

			fillBox(singleplayer, firstMin, firstMax, Blocks.STONE.defaultBlockState(), firstMin, Blocks.STONE.defaultBlockState());
			fillBox(singleplayer, secondMin, secondMax, Blocks.STONE.defaultBlockState(), secondMin, Blocks.STONE.defaultBlockState());

			select(singleplayer, firstMin, firstMax);
			runCommand(context, "vcs create " + FIRST);
			read(schematic(FIRST, 1));
			select(singleplayer, secondMin, secondMax);
			runCommand(context, "vcs create " + SECOND);
			read(schematic(SECOND, 1));
			// Creating selects, so the second build is the one shown now.
			assertSelected(waitForSelection(context, SECOND), SECOND, 1, secondBox);

			// A name that was never created leaves the selection alone.
			runCommand(context, "vcs select gametest-select-missing");
			context.waitTicks(5);
			assertSelected(context.computeOnClient(client -> ClientPlacements.selected()), SECOND, 1, secondBox);

			runCommand(context, "vcs select " + FIRST);
			assertSelected(waitForSelection(context, FIRST), FIRST, 1, firstBox);

			lookAt(context, firstMin, firstMax);
			screenshotLastFrame(context, "mcvcs-vcs-select");

			// Commit follows the selection: the first build gets a v2, the second does not.
			runCommand(context, "vcs commit");
			read(schematic(FIRST, 2));
			if (Files.exists(schematic(SECOND, 2))) {
				throw new AssertionError("Commit after /vcs select must not write " + schematic(SECOND, 2));
			}
			assertSelected(waitForSelection(context, FIRST, 2), FIRST, 2, firstBox);

			// Selecting the second build again shows its latest state, still v1.
			runCommand(context, "vcs select " + SECOND);
			assertSelected(waitForSelection(context, SECOND), SECOND, 1, secondBox);
		}
	}

	private static ClientPlacement waitForSelection(ClientGameTestContext context, String name) {
		return waitForSelection(context, name, 1);
	}

	private static ClientPlacement waitForSelection(ClientGameTestContext context, String name, int version) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name) && selected.head() == version;
		});
		return context.computeOnClient(client -> ClientPlacements.selected());
	}

	private static void assertSelected(ClientPlacement selected, String name, int version, BuildBox box) {
		if (selected == null) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but nothing is selected");
		}
		if (!selected.build().equals(name) || selected.head() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but got '" + selected.label() + "' v" + selected.head());
		}
		if (!selected.box().equals(box)) {
			throw new AssertionError("Expected selection box " + box + " but got " + selected.box());
		}
	}
}
