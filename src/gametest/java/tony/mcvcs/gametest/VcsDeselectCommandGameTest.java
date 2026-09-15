package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.client.diff.ClientDiff;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.preview.ClientPreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.ClientBuild;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs deselect} leaves the player with no selected build: the client stops drawing the box and any preview
 * or diff highlighting of the build, the server forgets the selection, and commands that need one refuse until
 * {@code /vcs select} makes one again.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsDeselectCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-deselect";

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 3x2x2 stone box in front of the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BuildBox box = new BuildBox(min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());

			// Nothing to deselect yet: the command refuses and nothing changes.
			runCommand(context, "vcs deselect");
			context.waitTicks(5);
			assertNothingSelected(context, singleplayer);

			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			assertSelected(waitForSelection(context, BUILD_NAME), BUILD_NAME, 1, box);

			// Preview v1 and highlight the diff against it, so both are up when the build is deselected.
			setBlock(singleplayer, min, Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs preview 1");
			context.waitFor(client -> PreviewManager.active() != null);
			runCommand(context, "vcs diff 1");
			context.waitFor(client -> DiffManager.active() != null);

			runCommand(context, "vcs deselect");
			context.waitFor(client -> ClientBuilds.selected() == null);
			assertNothingSelected(context, singleplayer);
			assertNoPreviewOrDiff(context);

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-deselect");

			// Commit needs a selection, so it writes nothing.
			runCommand(context, "vcs commit");
			context.waitTicks(5);
			if (Files.exists(schematic(BUILD_NAME, 2))) {
				throw new AssertionError("Commit after /vcs deselect must not write " + schematic(BUILD_NAME, 2));
			}
			assertNothingSelected(context, singleplayer);

			// The build itself is untouched and can be selected again.
			runCommand(context, "vcs select " + BUILD_NAME);
			assertSelected(waitForSelection(context, BUILD_NAME), BUILD_NAME, 1, box);
		}
	}

	private static void assertNothingSelected(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		Optional<Build> selected = singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			return BuildRegistry.selected(player);
		});
		if (selected.isPresent()) {
			throw new AssertionError("Expected no selection on the server but got " + selected.get());
		}
		ClientBuild shown = context.computeOnClient(client -> ClientBuilds.selected());
		if (shown != null) {
			throw new AssertionError("Expected no selection on the client but got '" + shown.name() + "' v" + shown.version());
		}
	}

	private static void assertNoPreviewOrDiff(ClientGameTestContext context) {
		context.waitTicks(5);
		ClientPreview preview = context.computeOnClient(client -> PreviewManager.active());
		if (preview != null) {
			throw new AssertionError("Expected no preview after /vcs deselect but '" + preview.name() + "' v" + preview.version() + " is shown");
		}
		ClientDiff diff = context.computeOnClient(client -> DiffManager.active());
		if (diff != null) {
			throw new AssertionError("Expected no diff after /vcs deselect but '" + diff.name() + "' v" + diff.version() + " is highlighted");
		}
	}

	private static ClientBuild waitForSelection(ClientGameTestContext context, String name) {
		context.waitFor(client -> {
			ClientBuild selected = ClientBuilds.selected();
			return selected != null && selected.name().equals(name) && selected.version() == 1;
		});
		return context.computeOnClient(client -> ClientBuilds.selected());
	}

	private static void assertSelected(ClientBuild selected, String name, int version, BuildBox box) {
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
