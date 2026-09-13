package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetProjects;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.nio.file.Files;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.client.project.ClientProjects;
import tony.mcvcs.project.Project;
import tony.mcvcs.project.ProjectBox;
import tony.mcvcs.project.ProjectRegistry;
import tony.mcvcs.project.ClientProject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs deselect} leaves the player with no selected project: the client stops drawing the box, the server
 * forgets the selection, and commands that need one refuse until {@code /vcs select} makes one again.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsDeselectCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-deselect";

	@Override
	public void runTest(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetProjects(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 3x2x2 stone box in front of the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			ProjectBox box = new ProjectBox(min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());

			// Nothing to deselect yet: the command refuses and nothing changes.
			runCommand(context, "vcs deselect");
			context.waitTicks(5);
			assertNothingSelected(context, singleplayer);

			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			assertSelected(waitForSelection(context, BUILD_NAME), BUILD_NAME, 1, box);

			runCommand(context, "vcs deselect");
			context.waitFor(client -> ClientProjects.selected() == null);
			assertNothingSelected(context, singleplayer);

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-deselect");

			// Commit needs a selection, so it writes nothing.
			runCommand(context, "vcs commit");
			context.waitTicks(5);
			if (Files.exists(schematic(BUILD_NAME, 2))) {
				throw new AssertionError("Commit after /vcs deselect must not write " + schematic(BUILD_NAME, 2));
			}
			assertNothingSelected(context, singleplayer);

			// The project itself is untouched and can be selected again.
			runCommand(context, "vcs select " + BUILD_NAME);
			assertSelected(waitForSelection(context, BUILD_NAME), BUILD_NAME, 1, box);
		}
	}

	private static void assertNothingSelected(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		Optional<Project> selected = singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			return ProjectRegistry.selected(player);
		});
		if (selected.isPresent()) {
			throw new AssertionError("Expected no selection on the server but got " + selected.get());
		}
		ClientProject shown = context.computeOnClient(client -> ClientProjects.selected());
		if (shown != null) {
			throw new AssertionError("Expected no selection on the client but got '" + shown.name() + "' v" + shown.version());
		}
	}

	private static ClientProject waitForSelection(ClientGameTestContext context, String name) {
		context.waitFor(client -> {
			ClientProject selected = ClientProjects.selected();
			return selected != null && selected.name().equals(name) && selected.version() == 1;
		});
		return context.computeOnClient(client -> ClientProjects.selected());
	}

	private static void assertSelected(ClientProject selected, String name, int version, ProjectBox box) {
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
