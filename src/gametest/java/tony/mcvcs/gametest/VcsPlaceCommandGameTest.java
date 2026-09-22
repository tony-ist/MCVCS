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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;

/**
 * {@code /vcs place} puts another copy of a build into the world as a placement of its own, where the player stands.
 * The copy lives its own life from then on: it is checked out and committed on its own, but its commits become
 * versions of the same build, which the first placement can then check out. Placements may not overlap, and blocks
 * standing where one would go are refused unless {@code -f} is given.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsPlaceCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-place";
	private static final String MAIN = BUILD_NAME + "/" + Build.MAIN;
	private static final String SECOND = "rig";

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

			// A placement lands below the player's feet, where they would otherwise be standing, so the test player
			// spectates: they stay where they are put instead of falling onto the copy they are about to make.
			runCommand(context, "gamemode spectator");

			// A 2x2x2 stone cube hovering in front of and to the right of the player, with a gold block in its top
			// south-east corner so the copies can be told apart block for block.
			BlockPos start = playerPos(singleplayer);
			BlockPos min = start.offset(2, 1, 2);
			BlockPos max = min.offset(1, 1, 1);
			BuildBox box = new BuildBox(min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), max, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));

			// A build that does not exist is refused before anything is pasted.
			assertOnlyMessage(run(context, "vcs place gametest-place-missing"), "No build named gametest-place-missing in this world");
			// So is a version the build does not have.
			assertOnlyMessage(run(context, "vcs place " + BUILD_NAME + " 2"), "Build " + BUILD_NAME + " only has versions 1 to 1");

			// Placing where the player stands puts the copy one block below their feet, extending east and south, the
			// same spot /vcs load and //paste would use. The player is moved well clear of the first placement first.
			BlockPos feet = hover(singleplayer, context, start.offset(0, 10, 20));
			BuildBox second = below(feet);
			List<Component> placed = run(context, "vcs place " + BUILD_NAME + " latest " + SECOND);
			assertOnlyMessage(placed, "Placed " + BUILD_NAME + "/" + SECOND + " v1 (2x2x2, 8 blocks) at " + second.min().toShortString());

			// The blocks are really there, gold corner and all, and the placement is selected and drawn.
			if (blockAt(singleplayer, second.max()) != Blocks.GOLD_BLOCK.defaultBlockState()
				|| blockAt(singleplayer, second.min()) != Blocks.STONE.defaultBlockState()) {
				throw new AssertionError("Expected v1 at " + second + " but found " + blockAt(singleplayer, second.min()) + " and " + blockAt(singleplayer, second.max()));
			}
			assertSelected(waitForSelection(context, BUILD_NAME + "/" + SECOND), second, 1);
			assertPlacements(singleplayer, List.of(Build.MAIN, SECOND));
			lookAt(context, second.min(), second.max());
			screenshotLastFrame(context, "mcvcs-vcs-place");

			// A second placement of the same build on the same spot would overlap the one just made.
			assertOnlyMessage(run(context, "vcs place " + BUILD_NAME),
				"Placing build " + BUILD_NAME + " here would overlap " + BUILD_NAME + "/" + SECOND + "; placements may not intersect");
			// And a name already taken is refused as well.
			assertOnlyMessage(run(context, "vcs place " + BUILD_NAME + " latest " + SECOND),
				"Build " + BUILD_NAME + " already has a placement called " + SECOND);

			// Blocks standing in the way are refused, then overwritten with -f. The player moves once more, and a dirt
			// block is put where the copy's north-west corner would land.
			BuildBox third = below(hover(singleplayer, context, start.offset(0, 10, 40)));
			setBlock(singleplayer, third.min(), Blocks.DIRT.defaultBlockState());
			assertOnlyMessage(run(context, "vcs place " + BUILD_NAME), "Placing build " + BUILD_NAME + " here would overwrite 1 block already standing in its 2x2x2 box at " + third.min().toShortString());
			if (blockAt(singleplayer, third.min()) != Blocks.DIRT.defaultBlockState()) {
				throw new AssertionError("A refused placement must leave the blocks alone but " + third.min().toShortString() + " holds " + blockAt(singleplayer, third.min()));
			}
			// Unnamed, so the placement is called p2: the lowest free number, main being the first.
			List<Component> forced = run(context, "vcs place " + BUILD_NAME + " latest " + Build.PLACEMENT_PREFIX + "2 " + "-f");
			assertOnlyMessage(forced, "Placed " + BUILD_NAME + "/" + Build.PLACEMENT_PREFIX + "2 v1 (2x2x2, 8 blocks) at " + third.min().toShortString() + ", overwriting 1 block that stood there");
			if (blockAt(singleplayer, third.min()) != Blocks.STONE.defaultBlockState()) {
				throw new AssertionError("Expected the forced placement to overwrite " + third.min().toShortString() + " but it holds " + blockAt(singleplayer, third.min()));
			}
			assertPlacements(singleplayer, List.of(Build.MAIN, Build.PLACEMENT_PREFIX + "2", SECOND));

			// Each placement lives its own life: a change committed at the second one becomes v2 of the build, which the
			// first one still does not hold. The gold corner of the second copy is dug out and committed.
			runCommand(context, "vcs select " + BUILD_NAME + " " + SECOND);
			waitForSelection(context, BUILD_NAME + "/" + SECOND);
			setBlock(singleplayer, second.max(), Blocks.AIR.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
			assertHead(singleplayer, SECOND, 2);
			assertHead(singleplayer, Build.MAIN, 1);
			// The first placement is untouched by that commit: its gold block is still there.
			if (blockAt(singleplayer, box.max()) != Blocks.GOLD_BLOCK.defaultBlockState()) {
				throw new AssertionError("A commit at one placement must not touch another, but " + box.max().toShortString() + " holds " + blockAt(singleplayer, box.max()));
			}

			// The version is the build's, though, so the first placement can check it out and loses its gold block too.
			runCommand(context, "vcs select " + BUILD_NAME + " " + Build.MAIN);
			waitForSelection(context, MAIN);
			runCommand(context, "vcs checkout 2");
			context.waitTicks(5);
			assertHead(singleplayer, Build.MAIN, 2);
			if (blockAt(singleplayer, box.max()) != Blocks.AIR.defaultBlockState()) {
				throw new AssertionError("Expected v2 checked out at " + MAIN + " to empty " + box.max().toShortString() + " but it holds " + blockAt(singleplayer, box.max()));
			}
			// And back again, which leaves the other placements where they were.
			runCommand(context, "vcs checkout 1");
			context.waitTicks(5);
			assertHead(singleplayer, Build.MAIN, 1);
			assertHead(singleplayer, SECOND, 2);
			if (blockAt(singleplayer, box.max()) != Blocks.GOLD_BLOCK.defaultBlockState()
				|| blockAt(singleplayer, second.max()) != Blocks.AIR.defaultBlockState()) {
				throw new AssertionError("Expected v1 at " + MAIN + " and v2 at the second placement but they hold "
					+ blockAt(singleplayer, box.max()) + " and " + blockAt(singleplayer, second.max()));
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

	/** The placement called {@code name} holds version {@code head}. */
	private static void assertHead(TestSingleplayerContext singleplayer, String name, int head) {
		Optional<BuildPlacement> placement = singleplayer.getServer().computeOnServer(server ->
			BuildRegistry.find(server, BUILD_NAME).flatMap(build -> BuildPlacement.of(build, name)));
		if (placement.isEmpty() || placement.get().head() != head) {
			throw new AssertionError("Expected placement '" + name + "' to hold v" + head + " but got " + placement.orElse(null));
		}
	}

	private static ClientPlacement waitForSelection(ClientGameTestContext context, String label) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.label().equals(label);
		});
		return context.computeOnClient(client -> ClientPlacements.selected());
	}

	private static void assertSelected(ClientPlacement selected, BuildBox box, int head) {
		if (selected == null || selected.head() != head || !selected.box().equals(box)) {
			throw new AssertionError("Expected the selection to hold v" + head + " at " + box + " but got " + selected);
		}
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
