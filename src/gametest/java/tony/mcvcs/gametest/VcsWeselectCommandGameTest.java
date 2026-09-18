package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.clearSelection;
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
import static tony.mcvcs.gametest.VcsTestSupport.weSelection;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.client.build.ClientBuilds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs weselect} sets the player's WorldEdit selection to the selected build's box, whether they had no
 * selection or one somewhere else, and follows the box when {@code /vcs expand} grows it. Without a selected build
 * it refuses and leaves the WorldEdit selection alone.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsWeselectCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-weselect";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 2x2x2 stone cube hovering one block above the ground in front of the player, so nothing touches it and
			// /vcs expand has nothing to take in until the test puts a block against it. No other test builds here:
			// build folders outlive a run and are only cleared by name, so two tests sharing a spot can collide over it.
			BlockPos min = playerPos(singleplayer).offset(6, 1, -6);
			BlockPos max = min.offset(1, 1, 1);
			BuildBox box = new BuildBox(min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());

			// Nothing selected yet: the command refuses, and the WorldEdit selection the player has stays as it was.
			BuildBox elsewhere = new BuildBox(min.offset(8, 0, 0), min.offset(9, 0, 0));
			select(singleplayer, elsewhere.min(), elsewhere.max());
			assertWeSelection(singleplayer, elsewhere);
			List<Component> unselected = run(context, "vcs weselect");
			assertOnlyMessage(unselected, "No build selected in this world");
			assertWeSelection(singleplayer, elsewhere);

			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			context.waitFor(client -> ClientBuilds.selected() != null && ClientBuilds.selected().name().equals(BUILD_NAME));

			// With no WorldEdit selection at all, the build's box becomes one.
			clearSelection(singleplayer);
			assertWeSelection(singleplayer, null);
			List<Component> fromNothing = run(context, "vcs weselect");
			assertOnlyMessage(fromNothing, "Selected build " + BUILD_NAME + " with WorldEdit: " + min.toShortString() + " to " + max.toShortString() + " (2x2x2, 8 blocks)");
			assertWeSelection(singleplayer, box);

			// A selection somewhere else is replaced rather than joined to the box.
			select(singleplayer, elsewhere.min(), elsewhere.max());
			assertWeSelection(singleplayer, elsewhere);
			runCommand(context, "vcs weselect");
			context.waitTicks(5);
			assertWeSelection(singleplayer, box);

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-weselect");

			// The box is the build's, not the one the player had when it was created: after /vcs expand grows it, the
			// WorldEdit selection follows the grown box.
			BlockPos corner = max.offset(1, 1, 1);
			setBlock(singleplayer, corner, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs expand");
			context.waitTicks(5);
			BuildBox expanded = new BuildBox(min, corner);
			runCommand(context, "vcs weselect");
			context.waitTicks(5);
			assertWeSelection(singleplayer, expanded);

			// Moving the WorldEdit selection afterwards cannot move the build: /vcs weselect brings the box back.
			select(singleplayer, elsewhere.min(), elsewhere.max());
			runCommand(context, "vcs weselect");
			context.waitTicks(5);
			assertWeSelection(singleplayer, expanded);
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	private static void assertOnlyMessage(List<Component> messages, String prefix) {
		if (messages.size() != 1 || !messages.get(0).getString().startsWith(prefix)) {
			throw new AssertionError("Expected only a message starting with '" + prefix + "' but got " + messages.stream().map(Component::getString).toList());
		}
	}

	/** @param expected the box the player's WorldEdit selection should cover, or {@code null} for no selection */
	private static void assertWeSelection(TestSingleplayerContext singleplayer, BuildBox expected) {
		BuildBox actual = weSelection(singleplayer);
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError("Expected WorldEdit selection " + expected + " but got " + actual);
		}
	}
}
