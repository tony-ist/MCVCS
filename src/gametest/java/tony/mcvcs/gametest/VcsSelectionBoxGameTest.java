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

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** The client learns which build is selected and draws its bounding box. */
@SuppressWarnings("UnstableApiUsage")
public class VcsSelectionBoxGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-selection";

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Nothing is selected yet, so nothing is drawn.
			context.waitTicks(5);
			if (context.computeOnClient(client -> ClientPlacements.selected()) != null) {
				throw new AssertionError("Expected no selected build before /vcs create");
			}

			// A 3x2x2 stone box in front of and to the right of the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BuildBox box = new BuildBox(min, max);

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

	private static ClientPlacement waitForSelection(ClientGameTestContext context, int version) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.head() == version;
		});
		return context.computeOnClient(client -> ClientPlacements.selected());
	}

	private static void assertSelected(ClientPlacement selected, String name, int version, BuildBox box) {
		if (!selected.build().equals(name) || selected.head() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " but got '" + selected.label() + "' v" + selected.head());
		}
		if (!selected.dimension().equals(Level.OVERWORLD)) {
			throw new AssertionError("Expected selection in the overworld but got " + selected.dimension());
		}
		if (!selected.box().equals(box)) {
			throw new AssertionError("Expected selection box " + box + " but got " + selected.box());
		}
	}
}
