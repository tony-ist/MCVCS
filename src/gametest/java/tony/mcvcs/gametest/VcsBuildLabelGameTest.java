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

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.client.label.BuildLabelRenderer;
import tony.mcvcs.client.project.ClientProjects;
import tony.mcvcs.project.ClientProject;
import tony.mcvcs.project.ProjectBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The client is told about every build in the world, not just the selected one, and labels each with its name while
 * the player is close: a build right in front of the player gets a label whether it is selected or not, one far away
 * gets none.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsBuildLabelGameTest implements FabricClientGameTest {
	private static final String NEAR = "gametest-label-near";
	private static final String FAR = "gametest-label-far";

	@Override
	public void runTest(ClientGameTestContext context) {
		// Before the world exists: the player is told the builds on join, so they must be gone by then.
		resetProjects(NEAR, FAR);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// No builds yet, so nothing is labelled.
			context.waitTicks(5);
			if (!context.computeOnClient(client -> ClientProjects.all()).isEmpty()) {
				throw new AssertionError("Expected no builds before /vcs create but got " + ClientProjects.all());
			}

			// A 3x2x2 stone box in front of the player and another one well beyond the label range.
			BlockPos nearMin = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos nearMax = nearMin.offset(2, 1, 1);
			ProjectBox nearBox = new ProjectBox(nearMin, nearMax);
			BlockPos farMin = playerPos(singleplayer).offset((int) BuildLabelRenderer.RANGE + 20, 0, 2);
			BlockPos farMax = farMin.offset(2, 1, 1);
			ProjectBox farBox = new ProjectBox(farMin, farMax);

			// The far build first, so the near one is created last and selected while the far one is not.
			fillBox(singleplayer, farMin, farMax, Blocks.STONE.defaultBlockState(), farMin, Blocks.STONE.defaultBlockState());
			select(singleplayer, farMin, farMax);
			runCommand(context, "vcs create " + FAR);
			read(schematic(FAR, 1));
			assertBuilds(waitForBuilds(context, 1), List.of(FAR), List.of(farBox));

			fillBox(singleplayer, nearMin, nearMax, Blocks.STONE.defaultBlockState(), nearMin, Blocks.STONE.defaultBlockState());
			select(singleplayer, nearMin, nearMax);
			runCommand(context, "vcs create " + NEAR);
			read(schematic(NEAR, 1));
			assertBuilds(waitForBuilds(context, 2), List.of(FAR, NEAR), List.of(farBox, nearBox));
			assertLabelled(context, nearBox, farBox);

			// Selected: the box and, above it, the label in the box's color.
			lookAt(context, nearMin, nearMax.above(2));
			screenshotLastFrame(context, "mcvcs-vcs-label-selected");

			// Deselecting drops the box but not the build or its label.
			runCommand(context, "vcs deselect");
			context.waitFor(client -> ClientProjects.selected() == null);
			assertBuilds(context.computeOnClient(client -> ClientProjects.all()), List.of(FAR, NEAR), List.of(farBox, nearBox));
			assertLabelled(context, nearBox, farBox);

			lookAt(context, nearMin, nearMax.above(2));
			screenshotLastFrame(context, "mcvcs-vcs-label");
		}
	}

	private static List<ClientProject> waitForBuilds(ClientGameTestContext context, int count) {
		context.waitFor(client -> ClientProjects.all().size() == count);
		return context.computeOnClient(client -> ClientProjects.all());
	}

	/** The builds the client knows, in the order the server lists them, each in the overworld at version 1. */
	private static void assertBuilds(List<ClientProject> builds, List<String> names, List<ProjectBox> boxes) {
		List<String> actualNames = builds.stream().map(ClientProject::name).toList();
		if (!actualNames.equals(names)) {
			throw new AssertionError("Expected builds " + names + " but got " + actualNames);
		}
		for (int i = 0; i < builds.size(); i++) {
			ClientProject build = builds.get(i);
			if (build.version() != 1 || !build.dimension().equals(Level.OVERWORLD) || !build.box().equals(boxes.get(i))) {
				throw new AssertionError("Expected build '" + names.get(i) + "' v1 in the overworld at " + boxes.get(i) + " but got " + build);
			}
		}
	}

	/** From where the player stands, the near build's label is fully visible and the far one's not at all. */
	private static void assertLabelled(ClientGameTestContext context, ProjectBox near, ProjectBox far) {
		Vec3 camera = context.computeOnClient(client -> client.gameRenderer.getMainCamera().position());
		float nearOpacity = BuildLabelRenderer.opacity(near, camera);
		float farOpacity = BuildLabelRenderer.opacity(far, camera);
		if (nearOpacity != 1.0f) {
			throw new AssertionError("Expected the label of the build at " + near + " to be fully visible from " + camera + " but its opacity is " + nearOpacity);
		}
		if (farOpacity != 0.0f) {
			throw new AssertionError("Expected no label for the build at " + far + " from " + camera + " but its opacity is " + farOpacity);
		}
	}
}
