package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.blockAt;
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
import static tony.mcvcs.gametest.VcsTestSupport.teleport;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.command.VcsCommandUnplace;

/**
 * {@code /vcs unplace} takes the selected placement out of its build and empties its box, or leaves its blocks
 * standing with {@code -k}. The build and all its versions stay on disk, so an unmodified placement goes at once;
 * only a box holding work no version does is asked about first, and {@code /vcs confirmUnplace} goes through with it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsUnplaceCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-unplace";
	private static final String KEPT = "kept";
	private static final String CLEARED = "cleared";
	private static final String EMPTIED = "emptied";

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

			// Nothing is selected yet, so there is nothing to unplace and nothing to confirm.
			assertOnlyMessage(run(context, "vcs unplace"), "Nothing selected in this world");
			assertOnlyMessage(run(context, "vcs confirmUnplace"), "Nothing to confirm");

			// A placement lands below the player's feet, where they would otherwise be standing, so the test player
			// spectates: they stay where they are put instead of falling onto the copy they are about to make.
			runCommand(context, "gamemode spectator");

			// A 2x2x2 stone cube in front of the player, then two more copies of it further along.
			BlockPos start = playerPos(singleplayer);
			BlockPos min = start.offset(2, 1, 2);
			BlockPos max = min.offset(1, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), max, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			read(schematic(BUILD_NAME, 1));

			BlockPos keptFeet = hover(singleplayer, context, start.offset(0, 10, 20));
			BuildBox kept = below(keptFeet);
			runCommand(context, "vcs place " + BUILD_NAME + " latest " + KEPT);
			runCommand(context, "vcs confirmPlace");
			waitForSelection(context, BUILD_NAME + "/" + KEPT);

			BuildBox cleared = below(hover(singleplayer, context, start.offset(0, 10, 40)));
			runCommand(context, "vcs place " + BUILD_NAME + " latest " + CLEARED);
			runCommand(context, "vcs confirmPlace");
			waitForSelection(context, BUILD_NAME + "/" + CLEARED);
			assertPlacements(singleplayer, List.of(CLEARED, KEPT, Build.MAIN));

			// A placement holding work no version does is asked about first, since removing it would lose that work,
			// and nothing happens until it is confirmed: the gold corner of this copy is dug out.
			setBlock(singleplayer, cleared.max(), Blocks.AIR.defaultBlockState());
			assertOnlyMessage(run(context, "vcs unplace"), "Placement " + BUILD_NAME + "/" + CLEARED + " is modified: 1 block differs from v1");
			assertPlacements(singleplayer, List.of(CLEARED, KEPT, Build.MAIN));
			if (blockAt(singleplayer, cleared.min()) != Blocks.STONE.defaultBlockState()) {
				throw new AssertionError("Asking must leave the blocks alone but " + cleared.min().toShortString() + " holds " + blockAt(singleplayer, cleared.min()));
			}

			// Confirming removes it and empties its box, and the build keeps its version on disk.
			List<Component> removed = run(context, "vcs confirmUnplace");
			assertOnlyMessage(removed, "Removed placement " + BUILD_NAME + "/" + CLEARED + " and emptied its box");
			assertPlacements(singleplayer, List.of(KEPT, Build.MAIN));
			if (blockAt(singleplayer, cleared.min()) != Blocks.AIR.defaultBlockState()
				|| blockAt(singleplayer, cleared.max()) != Blocks.AIR.defaultBlockState()) {
				throw new AssertionError("Expected " + cleared + " to be empty but it holds " + blockAt(singleplayer, cleared.min()) + " and " + blockAt(singleplayer, cleared.max()));
			}
			if (!Files.isRegularFile(schematic(BUILD_NAME, 1))) {
				throw new AssertionError("Unplacing must keep the build's versions, but " + schematic(BUILD_NAME, 1) + " is gone");
			}
			// Whoever had it selected has nothing selected now.
			context.waitFor(client -> ClientPlacements.selected() == null);

			// This one holds exactly what its version does, so it goes at once, with nothing to confirm afterwards.
			runCommand(context, "vcs select " + BUILD_NAME + " " + KEPT);
			waitForSelection(context, BUILD_NAME + "/" + KEPT);
			lookAt(context, kept.min(), kept.max());
			screenshotLastFrame(context, "mcvcs-vcs-unplace");
			List<Component> left = run(context, "vcs unplace " + VcsCommandUnplace.KEEP);
			assertOnlyMessage(left, "Removed placement " + BUILD_NAME + "/" + KEPT + "; its blocks were left standing");
			assertOnlyMessage(run(context, "vcs confirmUnplace"), "Nothing to confirm");
			assertPlacements(singleplayer, List.of(Build.MAIN));
			if (blockAt(singleplayer, kept.max()) != Blocks.GOLD_BLOCK.defaultBlockState()) {
				throw new AssertionError("Expected the blocks of " + KEPT + " to stay but " + kept.max().toShortString() + " holds " + blockAt(singleplayer, kept.max()));
			}

			// The blocks left standing belong to nobody now, so placing there again is refused until they are gone:
			// the copy is shown all the same, since it is only refused once it is confirmed.
			teleport(singleplayer, keptFeet);
			context.waitTicks(2);
			assertOnlyMessage(run(context, "vcs place " + BUILD_NAME), "Showing " + BUILD_NAME + "/" + Build.PLACEMENT_PREFIX + "2");
			assertOnlyMessage(run(context, "vcs confirmPlace"), "Placing build " + BUILD_NAME + " here would overwrite 8 blocks already standing");
			runCommand(context, "vcs cancelPlace");

			// Without -k the box is emptied, so the last placement leaves the ground clear behind it: it holds its
			// version exactly, having only just been placed, so it needs no confirming either.
			runCommand(context, "vcs place " + BUILD_NAME + " latest " + EMPTIED + " " + "-f");
			runCommand(context, "vcs confirmPlace");
			waitForSelection(context, BUILD_NAME + "/" + EMPTIED);
			List<Component> emptied = run(context, "vcs unplace");
			assertOnlyMessage(emptied, "Removed placement " + BUILD_NAME + "/" + EMPTIED + " and emptied its box");
			assertPlacements(singleplayer, List.of(Build.MAIN));
			if (blockAt(singleplayer, kept.min()) != Blocks.AIR.defaultBlockState()
				|| blockAt(singleplayer, kept.max()) != Blocks.AIR.defaultBlockState()) {
				throw new AssertionError("Expected " + kept + " to be empty but it holds " + blockAt(singleplayer, kept.min()) + " and " + blockAt(singleplayer, kept.max()));
			}
		}
	}

	/**
	 * Puts the spectating player at {@code pos} and empties the 2x2x2 box a placement would land in below them, so
	 * nothing but what a test puts there is ever in the way. Returns where their feet really ended up.
	 */
	private static BlockPos hover(TestSingleplayerContext singleplayer, ClientGameTestContext context, BlockPos pos) {
		teleport(singleplayer, pos);
		context.waitTicks(2);
		BlockPos feet = playerPos(singleplayer);
		BuildBox box = below(feet);
		fillBox(singleplayer, box.min(), box.max(), Blocks.AIR.defaultBlockState(), box.min(), Blocks.AIR.defaultBlockState());
		return feet;
	}

	/** Where a 2x2x2 copy lands for a player whose feet are at {@code feet}: below them, extending east and south. */
	private static BuildBox below(BlockPos feet) {
		return new BuildBox(feet.offset(0, -2, 0), feet.offset(1, -1, 1));
	}

	/** The build's placements on disk, by name, in order. */
	private static void assertPlacements(TestSingleplayerContext singleplayer, List<String> expected) {
		List<String> actual = singleplayer.getServer().computeOnServer(server ->
			BuildRegistry.find(server, BUILD_NAME).map(Build::placementNames).orElse(List.of()));
		if (!actual.equals(expected)) {
			throw new AssertionError("Expected placements " + expected + " but got " + actual);
		}
	}

	private static void waitForSelection(ClientGameTestContext context, String label) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.label().equals(label);
		});
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		// Whatever an earlier command is still answering belongs to that command, not to this one.
		context.waitTicks(5);
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
}
