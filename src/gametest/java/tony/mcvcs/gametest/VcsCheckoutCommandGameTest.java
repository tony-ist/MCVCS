package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.main;
import static tony.mcvcs.gametest.VcsTestSupport.mainBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.putItem;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;
import static tony.mcvcs.gametest.VcsTestSupport.suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.client.diff.ClientDiff;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.preview.ClientPreview;
import tony.mcvcs.client.preview.PreviewManager;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * {@code /vcs checkout <version | latest> [-f]} empties the selected build's box and puts a version back in it without
 * block updates, so hovering sand stays up and an observer watching a block does not fire its piston; refuses while
 * the box holds uncommitted changes unless {@code -f} is given, which overwrites them; stops any preview and diff
 * highlighting the player had up; and puts a version committed before {@code /vcs expand} back where it was built,
 * with the rest of the grown box left empty.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsCheckoutCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-checkout";
	private static final String REDSTONE_NAME = "gametest-checkout-redstone";
	/** How the build's one placement is written in chat: its build and its own name. */
	private static final String LABEL = BUILD_NAME + "/" + Build.MAIN;
	private static final String REDSTONE_LABEL = REDSTONE_NAME + "/" + Build.MAIN;
	/** Ticks a freshly placed sand block takes to start falling, with some to spare: {@code FallingBlock#getDelayAfterPlace} is 2. */
	private static final int FALL_TICKS = 10;
	/** Ticks for an observer pulse to fire a piston and the piston to extend and retract again, with some to spare. */
	private static final int REDSTONE_TICKS = 20;

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME, REDSTONE_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// v1: a 3x3x3 stone cube hovering one block above the ground in front of and to the right of the player, with
			// a hole in the bottom of its middle column, a sand block hovering over that hole, a barrel with a diamond in
			// the bottom north-west corner and a gold block in the top south-east one. The sand is placed without an
			// update so it does not fall; a checkout that updated blocks would drop it into the hole.
			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(2, 2, 2);
			BuildBox box = new BuildBox(min, max);
			BlockPos hole = min.offset(1, 0, 1);
			BlockPos sand = min.offset(1, 1, 1);
			BlockPos barrel = min;
			BlockPos gold = max;
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, hole, Blocks.AIR.defaultBlockState());
			setBlockWithoutUpdate(singleplayer, sand, Blocks.SAND.defaultBlockState());
			setBlock(singleplayer, barrel, Blocks.BARREL.defaultBlockState());
			putItem(singleplayer, barrel, 0, new ItemStack(Items.DIAMOND));
			context.waitTicks(FALL_TICKS);
			assertWorldBlock(singleplayer, sand, Blocks.SAND);
			select(singleplayer, min, max);

			// Nothing to check out before a build has been created.
			List<Component> none = run(context, "vcs checkout 1");
			assertOnlyMessage(none, "Nothing selected in this world");

			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));
			assertSuggestions(singleplayer, "vcs checkout ", List.of("-h", "latest", "1"));

			// v2 differs from v1 in one block and in the barrel's contents: the gold block is dug out and the diamond taken.
			setBlock(singleplayer, gold, Blocks.AIR.defaultBlockState());
			putItem(singleplayer, barrel, 0, ItemStack.EMPTY);

			// Those are uncommitted, so checking out anything is refused and the box is left alone.
			List<Component> modified = run(context, "vcs checkout 1");
			assertOnlyMessage(modified, "Placement " + LABEL + " is modified: 2 blocks differ from v1; run /vcs commit before checking out");
			assertWorldBlock(singleplayer, gold, Blocks.AIR);
			assertBarrel(singleplayer, barrel, ItemStack.EMPTY);

			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
			assertSuggestions(singleplayer, "vcs checkout ", List.of("-h", "latest", "1", "2"));

			// Nothing to check out beyond the latest version.
			List<Component> beyond = run(context, "vcs checkout 3");
			assertOnlyMessage(beyond, "Build " + BUILD_NAME + " only has versions 1 to 2");
			assertWorldBlock(singleplayer, gold, Blocks.AIR);

			// Preview v1 and highlight the diff against it, so both are up when v1 is checked out: the preview would hide
			// the blocks just placed and the diff highlights blocks that are gone, so the checkout stops both.
			runCommand(context, "vcs preview 1");
			context.waitFor(client -> PreviewManager.active() != null);
			runCommand(context, "vcs diff 1");
			context.waitFor(client -> DiffManager.active() != null);

			// v1 comes back block for block, barrel contents included, and the sand stays up: no block was updated.
			List<Component> checkedOut = run(context, "vcs checkout 1");
			assertOnlyMessage(checkedOut, "Checked out " + LABEL + " v1 (3x3x3, 27 blocks) without block updates; it now holds v1 rather than the latest v2, run /vcs checkout latest to go back to it");
			context.waitTicks(FALL_TICKS);
			assertNoPreviewOrDiff(context);
			assertWorldBlock(singleplayer, gold, Blocks.GOLD_BLOCK);
			assertWorldBlock(singleplayer, sand, Blocks.SAND);
			assertWorldBlock(singleplayer, hole, Blocks.AIR);
			assertBarrel(singleplayer, barrel, new ItemStack(Items.DIAMOND));
			assertMatches(context, BUILD_NAME, 1);
			// The build keeps its two versions and its box; only the version the box holds moved, and /vcs diff without
			// a version now compares against that one.
			assertBuild(singleplayer, BUILD_NAME, 2, 1, box);
			assertOnlyMessage(run(context, "vcs diff"), "Placement " + LABEL + " matches v1");

			// The box matches v1 now, not v2, but that is not a modification: checking out v2 is allowed and puts the
			// world back the way v2 has it. A change made on top of v1 is a modification of v1, though, and blocks that.
			// The hole is right under the sand, so it is filled without an update that would make the sand fall.
			setBlockWithoutUpdate(singleplayer, hole, Blocks.STONE.defaultBlockState());
			List<Component> onTop = run(context, "vcs checkout 2");
			assertOnlyMessage(onTop, "Placement " + LABEL + " is modified: 1 block differs from v1; run /vcs commit before checking out, /vcs diff to see the changes, or add -f to discard them");
			assertWorldBlock(singleplayer, gold, Blocks.GOLD_BLOCK);
			assertWorldBlock(singleplayer, hole, Blocks.STONE);

			// A WorldEdit edit of the player's own, made before the checkout: a stone block hovering above the box. It is
			// the only thing in their WorldEdit history, and the checkout must not join it there.
			BlockPos above = min.offset(0, 4, 0);
			editWithWorldEdit(singleplayer, above, BlockTypes.STONE);
			assertWorldBlock(singleplayer, above, Blocks.STONE);

			// -f goes through anyway, says how many blocks it overwrote, and the world is v2 with the hole empty again.
			// The latest version can be named as such rather than by number.
			assertSuggestions(singleplayer, "vcs checkout latest ", List.of("-f"));
			assertSuggestions(singleplayer, "vcs checkout 2 ", List.of("-f"));
			List<Component> latest = run(context, "vcs checkout latest -f");
			assertOnlyMessage(latest, "Checked out " + LABEL + " v2 (3x3x3, 27 blocks) without block updates, overwriting 1 block");
			context.waitTicks(FALL_TICKS);
			assertWorldBlock(singleplayer, gold, Blocks.AIR);
			assertWorldBlock(singleplayer, hole, Blocks.AIR);
			assertWorldBlock(singleplayer, sand, Blocks.SAND);
			assertBarrel(singleplayer, barrel, ItemStack.EMPTY);
			assertMatches(context, BUILD_NAME, 2);
			assertBuild(singleplayer, BUILD_NAME, 2, 2, box);

			// The checkout is not in WorldEdit's history: //undo reverts the player's own edit from before it, the stone
			// above the box, and leaves the box as checked out; after that there is nothing left to undo, so the
			// overwritten block does not come back either.
			assertOnlyMessage(run(context, "/undo"), "Undid 1 available edits.");
			assertWorldBlock(singleplayer, above, Blocks.AIR);
			assertWorldBlock(singleplayer, gold, Blocks.AIR);
			assertWorldBlock(singleplayer, hole, Blocks.AIR);
			assertMatches(context, BUILD_NAME, 2);
			assertOnlyMessage(run(context, "/undo"), "Nothing left to undo.");
			assertWorldBlock(singleplayer, hole, Blocks.AIR);
			assertMatches(context, BUILD_NAME, 2);

			// Without uncommitted changes -f changes nothing about the message.
			List<Component> forcedClean = run(context, "vcs checkout 1 -f");
			assertOnlyMessage(forcedClean, "Checked out " + LABEL + " v1 (3x3x3, 27 blocks) without block updates; it now holds v1 rather than the latest v2, run /vcs checkout latest to go back to it");
			assertWorldBlock(singleplayer, gold, Blocks.GOLD_BLOCK);
			assertMatches(context, BUILD_NAME, 1);
			List<Component> backToLatest = run(context, "vcs checkout latest");
			assertOnlyMessage(backToLatest, "Checked out " + LABEL + " v2 (3x3x3, 27 blocks) without block updates");
			assertWorldBlock(singleplayer, gold, Blocks.AIR);
			assertBuild(singleplayer, BUILD_NAME, 2, 2, box);

			// A gold block touching the top south-east corner diagonally makes the box grow to 4x4x4 as v3. Checking out
			// v1 then empties the whole grown box and puts v1 back at the place it was built, so the outer layer is air.
			BlockPos corner = max.offset(1, 1, 1);
			setBlock(singleplayer, corner, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs expand");
			read(schematic(BUILD_NAME, 3));
			BuildBox expanded = new BuildBox(min, corner);
			assertBuild(singleplayer, BUILD_NAME, 3, 3, expanded);
			assertSuggestions(singleplayer, "vcs checkout ", List.of("-h", "latest", "1", "2", "3"));

			List<Component> smaller = run(context, "vcs checkout 1");
			assertOnlyMessage(smaller, "Checked out " + LABEL + " v1 (3x3x3, 27 blocks) without block updates; it now holds v1 rather than the latest v3, run /vcs checkout latest to go back to it");
			context.waitTicks(FALL_TICKS);
			assertWorldBlock(singleplayer, corner, Blocks.AIR);
			assertWorldBlock(singleplayer, max.offset(1, 0, 0), Blocks.AIR);
			assertWorldBlock(singleplayer, min, Blocks.BARREL);
			assertWorldBlock(singleplayer, gold, Blocks.GOLD_BLOCK);
			assertWorldBlock(singleplayer, sand, Blocks.SAND);
			assertWorldBlock(singleplayer, hole, Blocks.AIR);
			assertBarrel(singleplayer, barrel, new ItemStack(Items.DIAMOND));
			// v1 is looked at through the grown box, so the world matches it, air around it included.
			assertMatches(context, BUILD_NAME, 1);
			assertBuild(singleplayer, BUILD_NAME, 3, 1, box);

			lookAt(context, min, corner);
			screenshotLastFrame(context, "mcvcs-vcs-checkout");

			// And back to the grown version, by number this time.
			List<Component> grown = run(context, "vcs checkout 3");
			assertOnlyMessage(grown, "Checked out " + LABEL + " v3 (4x4x4, 64 blocks) without block updates");
			assertWorldBlock(singleplayer, corner, Blocks.GOLD_BLOCK);
			assertWorldBlock(singleplayer, gold, Blocks.AIR);
			assertMatches(context, BUILD_NAME, 3);
			assertBuild(singleplayer, BUILD_NAME, 3, 3, expanded);

			assertRedstoneLeftAlone(context, singleplayer, min.offset(0, 0, 10));
		}
	}

	/**
	 * A contraption that reacts to block updates: a line of stone, an observer watching the stone, a piston fed by the
	 * observer's back and a gold block in front of the piston, hovering in the air, with one empty slot after it. A
	 * checkout that placed the stone with updates would pulse the observer, fire the piston and push the gold block
	 * into that slot; {@code /vcs checkout} leaves everything where the version has it.
	 *
	 * @param start where the line begins; it runs east from there
	 */
	private static void assertRedstoneLeftAlone(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos start) {
		BlockPos stone = start;
		BlockPos observer = start.east(1);
		BlockPos piston = start.east(2);
		BlockPos gold = start.east(3);
		BlockPos end = start.east(4);
		BlockState observerState = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST);
		BlockState pistonState = Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);

		// Built west to east, so the observer is in place before anything changes next to it, and nothing changes in
		// front of it: it never fires while the line goes up.
		setBlock(singleplayer, stone, Blocks.STONE.defaultBlockState());
		setBlock(singleplayer, observer, observerState);
		setBlock(singleplayer, piston, pistonState);
		setBlock(singleplayer, gold, Blocks.GOLD_BLOCK.defaultBlockState());
		context.waitTicks(REDSTONE_TICKS);
		assertWorldState(singleplayer, observer, observerState);
		assertWorldState(singleplayer, piston, pistonState);
		assertWorldBlock(singleplayer, gold, Blocks.GOLD_BLOCK);
		assertWorldBlock(singleplayer, end, Blocks.AIR);

		// v1 is the line with the empty slot; v2 has a stone block in the slot.
		select(singleplayer, start, end);
		runCommand(context, "vcs create " + REDSTONE_NAME);
		read(schematic(REDSTONE_NAME, 1));
		setBlock(singleplayer, end, Blocks.STONE.defaultBlockState());
		runCommand(context, "vcs commit");
		read(schematic(REDSTONE_NAME, 2));

		// Checking out v1 empties the slot again and puts the stone, observer, piston and gold block back. None of them
		// notices: the observer stays unpowered, the piston retracted and the gold block where it was, so the world is v1.
		List<Component> checkedOut = run(context, "vcs checkout 1");
		assertOnlyMessage(checkedOut, "Checked out " + REDSTONE_LABEL + " v1 (5x1x1, 5 blocks) without block updates");
		context.waitTicks(REDSTONE_TICKS);
		assertWorldBlock(singleplayer, stone, Blocks.STONE);
		assertWorldState(singleplayer, observer, observerState);
		assertWorldState(singleplayer, piston, pistonState);
		assertWorldBlock(singleplayer, gold, Blocks.GOLD_BLOCK);
		assertWorldBlock(singleplayer, end, Blocks.AIR);
		assertMatches(context, REDSTONE_NAME, 1);

		// The contraption is live, though: a single real block update in front of the observer fires the piston, which
		// pushes the gold block into the slot and retracts, so the world no longer matches v1.
		setBlock(singleplayer, stone, Blocks.DIAMOND_BLOCK.defaultBlockState());
		context.waitTicks(REDSTONE_TICKS);
		assertWorldBlock(singleplayer, gold, Blocks.AIR);
		assertWorldBlock(singleplayer, end, Blocks.GOLD_BLOCK);
		assertWorldState(singleplayer, piston, pistonState);
		assertOnlyMessage(run(context, "vcs diff 1"), "3 blocks in " + REDSTONE_LABEL + " differ from v1 (1 added, 1 removed, 1 changed)");
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		// A reply to an earlier command, say a create that took a while to write its schematic, may still be on its way
		// and would be taken for this command's if the list were cleared while it is.
		awaitQuiet(context);
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		awaitQuiet(context);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/** Waits until no game message has arrived for a few ticks. */
	private static void awaitQuiet(ClientGameTestContext context) {
		int seen;
		do {
			seen = context.computeOnClient(client -> RECEIVED.size());
			context.waitTicks(3);
		} while (context.computeOnClient(client -> RECEIVED.size()) != seen);
	}

	private static void assertOnlyMessage(List<Component> messages, String prefix) {
		if (messages.size() != 1 || !messages.get(0).getString().startsWith(prefix)) {
			throw new AssertionError("Expected only a message starting with '" + prefix + "' but got " + messages.stream().map(Component::getString).toList());
		}
	}

	/** The client shows no preview and highlights no diff. */
	private static void assertNoPreviewOrDiff(ClientGameTestContext context) {
		ClientPreview preview = context.computeOnClient(client -> PreviewManager.active());
		if (preview != null) {
			throw new AssertionError("Expected no preview after /vcs checkout but '" + preview.name() + "' v" + preview.version() + " is shown");
		}
		ClientDiff diff = context.computeOnClient(client -> DiffManager.active());
		if (diff != null) {
			throw new AssertionError("Expected no diff after /vcs checkout but '" + diff.name() + "' v" + diff.version() + " is highlighted");
		}
	}

	/** {@code /vcs diff <version>} finds nothing, so the box holds exactly that version. */
	private static void assertMatches(ClientGameTestContext context, String name, int version) {
		assertOnlyMessage(run(context, "vcs diff " + version), "Placement " + name + "/" + Build.MAIN + " matches v" + version);
	}

	/**
	 * Sets {@code pos} to {@code block} through WorldEdit, remembered by the player's session the way {@code //set}
	 * would be, so it is what {@code //undo} reverts next.
	 */
	private static void editWithWorldEdit(TestSingleplayerContext singleplayer, BlockPos pos, BlockType block) {
		singleplayer.getServer().runOnServer(server -> {
			FabricAdapter adapter = FabricAdapter.get();
			Player actor = adapter.fromNativePlayer(server.getPlayerList().getPlayers().get(0));
			LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
			EditSession editSession = session.createEditSession(actor);
			try {
				editSession.setBlock(adapter.adapt(pos), block.getDefaultState());
			} catch (WorldEditException e) {
				throw new AssertionError("Failed to set " + block + " at " + pos + " with WorldEdit", e);
			} finally {
				editSession.close();
			}
			session.remember(editSession);
		});
	}

	/** Sets the block the way a checkout must: with the client told but no block update, so sand placed in the air stays there. */
	private static void setBlockWithoutUpdate(TestSingleplayerContext singleplayer, BlockPos pos, BlockState state) {
		singleplayer.getServer().runOnServer(server -> server.overworld().setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE));
	}

	private static void assertWorldBlock(TestSingleplayerContext singleplayer, BlockPos pos, Block expected) {
		Block actual = singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(pos).getBlock());
		if (actual != expected) {
			throw new AssertionError("Expected the world to hold " + expected + " at " + pos + " but it holds " + actual);
		}
	}

	/** The block at {@code pos} is exactly {@code expected}, properties such as facing, powered and extended included. */
	private static void assertWorldState(TestSingleplayerContext singleplayer, BlockPos pos, BlockState expected) {
		BlockState actual = singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(pos));
		if (actual != expected) {
			throw new AssertionError("Expected the world to hold " + expected + " at " + pos + " but it holds " + actual);
		}
	}

	/** The first slot of the container at {@code pos} holds {@code expected}. */
	private static void assertBarrel(TestSingleplayerContext singleplayer, BlockPos pos, ItemStack expected) {
		ItemStack actual = singleplayer.getServer().computeOnServer(server -> {
			if (!(server.overworld().getBlockEntity(pos) instanceof Container container)) {
				throw new AssertionError("Expected a container at " + pos + " but found " + server.overworld().getBlockState(pos));
			}
			return container.getItem(0).copy();
		});
		if (!ItemStack.matches(actual, expected)) {
			throw new AssertionError("Expected the container at " + pos + " to hold " + expected + " but it holds " + actual);
		}
	}

	/** The build on disk has {@code version} versions, its box holds {@code head} and the box is {@code box}. */
	private static void assertBuild(TestSingleplayerContext singleplayer, String name, int version, int head, BuildBox box) {
		Optional<Build> build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, name));
		if (build.isEmpty() || build.get().version() != version || main(build.get()).head() != head || !mainBox(build.get()).equals(box)) {
			throw new AssertionError("Expected build '" + name + "' v" + version + " holding v" + head + " with box " + box + " but got " + build.orElse(null));
		}
	}

	private static void assertSuggestions(TestSingleplayerContext singleplayer, String command, List<String> expected) {
		List<String> actual = suggestions(singleplayer, command);
		if (!new HashSet<>(actual).equals(new HashSet<>(expected))) {
			throw new AssertionError("Expected /" + command + " to suggest " + expected + " but got " + actual);
		}
	}
}
