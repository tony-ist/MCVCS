package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.blockAt;
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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.ClientBuild;
import tony.mcvcs.client.build.ClientBuilds;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /vcs create} without a WorldEdit selection waits for the player to click a block: a punch with anything in
 * hand or a right-click with an empty hand. The build is grown from that block over everything connected to it, the
 * block itself is neither broken nor used, and a box that would overlap another build is refused. Another
 * {@code /vcs create} replaces the click that is being waited for.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsCreateOnClickGameTest extends VcsGameTest {
	private static final String PUNCHED_NAME = "gametest-click-punched";
	private static final String USED_NAME = "gametest-click-used";
	private static final String OVERLAPPING_NAME = "gametest-click-overlap";
	private static final String REPLACED_NAME = "gametest-click-replaced";
	private static final String SELECTED_NAME = "gametest-click-selected";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(PUNCHED_NAME, USED_NAME, OVERLAPPING_NAME, REPLACED_NAME, SELECTED_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// A selection left by an earlier test would make create use it rather than wait for a click.
			clearSelection(singleplayer);

			// A 2x2x2 stone cube hovering one block above the ground in front of and to the right of the player, with a
			// gold block touching its top south-east corner only diagonally: the build the click has to grow over.
			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(1, 1, 1);
			BlockPos gold = max.offset(1, 1, 1);
			BuildBox punched = new BuildBox(min, gold);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			setBlock(singleplayer, gold, Blocks.GOLD_BLOCK.defaultBlockState());

			// Without a selection nothing is created yet; the player is asked to click.
			List<Component> armed = run(context, "vcs create " + PUNCHED_NAME);
			assertOnlyMessage(armed, "No WorldEdit selection; punch a block of the build, or right-click it with an empty hand, to create build " + PUNCHED_NAME);
			assertNoBuild(PUNCHED_NAME);

			// Punching the cube creates the build from the whole 3x3x3 it forms with the gold block, and breaks nothing.
			lookAt(context, min, max);
			List<Component> created = click(context, options -> options.keyAttack);
			assertOnlyMessage(created, "Created build " + PUNCHED_NAME + " (3x3x3, 27 blocks) at");
			assertBox(context, singleplayer, PUNCHED_NAME, punched);
			assertState(singleplayer, min, Blocks.STONE.defaultBlockState());
			assertState(singleplayer, gold, Blocks.GOLD_BLOCK.defaultBlockState());
			Clipboard clipboard = read(schematic(PUNCHED_NAME, 1));
			assertSize(clipboard, BlockVector3.at(3, 3, 3));
			assertBlock(clipboard, adapter.adapt(min), BlockTypes.STONE);
			assertBlock(clipboard, adapter.adapt(max), BlockTypes.STONE);
			assertBlock(clipboard, adapter.adapt(gold), BlockTypes.GOLD_BLOCK);
			assertBlock(clipboard, adapter.adapt(max.offset(1, 0, 0)), BlockTypes.AIR);
			screenshotLastFrame(context, "mcvcs-vcs-create-on-click");

			// The click is used up: punching again is an ordinary punch, which says nothing.
			List<Component> ordinary = click(context, options -> options.keyAttack);
			assertNoMessage(ordinary);
			assertState(singleplayer, min, Blocks.STONE.defaultBlockState());

			// A note block hovering to the left of the player. Right-clicking it with the empty hand creates a build of
			// just that block, and does not use it: an ordinary right-click would raise its note.
			BlockPos note = playerPos(singleplayer).offset(-3, 1, 2);
			BlockState silent = Blocks.NOTE_BLOCK.defaultBlockState();
			setBlock(singleplayer, note, silent);
			runCommand(context, "vcs create " + USED_NAME);
			lookAt(context, note, note);
			List<Component> used = click(context, options -> options.keyUse);
			assertOnlyMessage(used, "Created build " + USED_NAME + " (1x1x1, 1 blocks) at");
			assertBox(context, singleplayer, USED_NAME, new BuildBox(note, note));
			assertState(singleplayer, note, silent);
			if (silent.getValue(NoteBlock.NOTE) != 0) {
				throw new AssertionError("Expected the note block to start at note 0");
			}

			// Without a click being waited for, the same right-click plays the note block and raises its note.
			click(context, options -> options.keyUse);
			if (blockAt(singleplayer, note).getValue(NoteBlock.NOTE) != 1) {
				throw new AssertionError("Expected an ordinary right-click to raise the note but the block is " + blockAt(singleplayer, note));
			}

			// A block against the first build's west face, between it and the player: the box grown from it would cover
			// that build, which is refused, and the click is used up all the same.
			BlockPos touching = min.offset(-1, 0, 0);
			setBlock(singleplayer, touching, Blocks.STONE.defaultBlockState());
			runCommand(context, "vcs create " + OVERLAPPING_NAME);
			lookAt(context, touching, touching);
			List<Component> refused = click(context, options -> options.keyAttack);
			assertOnlyMessage(refused, "Build " + OVERLAPPING_NAME + " would overlap build " + PUNCHED_NAME + "; builds may not intersect");
			assertNoBuild(OVERLAPPING_NAME);
			assertState(singleplayer, touching, Blocks.STONE.defaultBlockState());
			assertNoMessage(click(context, options -> options.keyAttack));

			// A create with a selection replaces the click an earlier one without is waiting for: the earlier build is
			// never made, however much the player clicks afterwards. The selection is a 2x2x2 cube behind the player's right.
			BlockPos selectedMin = playerPos(singleplayer).offset(2, 1, -3);
			BlockPos selectedMax = selectedMin.offset(1, 1, 1);
			fillBox(singleplayer, selectedMin, selectedMax, Blocks.STONE.defaultBlockState(), selectedMin, Blocks.STONE.defaultBlockState());
			runCommand(context, "vcs create " + REPLACED_NAME);
			select(singleplayer, selectedMin, selectedMax);
			List<Component> selected = run(context, "vcs create " + SELECTED_NAME);
			assertOnlyMessage(selected, "Created build " + SELECTED_NAME + " (2x2x2, 8 blocks) at");
			lookAt(context, selectedMin, selectedMax);
			assertNoMessage(click(context, options -> options.keyAttack));
			assertNoBuild(REPLACED_NAME);
			assertBox(context, singleplayer, SELECTED_NAME, new BuildBox(selectedMin, selectedMax));
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/** Presses the attack or use key once, at whatever the crosshair is on, and returns every game message that produced. */
	private static List<Component> click(ClientGameTestContext context, Function<Options, KeyMapping> key) {
		context.runOnClient(client -> RECEIVED.clear());
		context.getInput().pressKey(key);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	private static void assertOnlyMessage(List<Component> messages, String prefix) {
		if (messages.size() != 1 || !messages.get(0).getString().startsWith(prefix)) {
			throw new AssertionError("Expected only a message starting with '" + prefix + "' but got " + messages.stream().map(Component::getString).toList());
		}
	}

	private static void assertNoMessage(List<Component> messages) {
		if (!messages.isEmpty()) {
			throw new AssertionError("Expected no message but got " + messages.stream().map(Component::getString).toList());
		}
	}

	private static void assertNoBuild(String name) {
		if (Files.exists(BuildStorage.directory(name))) {
			throw new AssertionError("Expected no build folder " + BuildStorage.directory(name));
		}
	}

	private static void assertState(TestSingleplayerContext singleplayer, BlockPos pos, BlockState expected) {
		BlockState actual = blockAt(singleplayer, pos);
		if (!actual.equals(expected)) {
			throw new AssertionError("Expected " + expected + " at " + pos + " but the server has " + actual);
		}
	}

	/** The build on disk is at v1 with the given box and, once the server has told it, the client has it selected. */
	private static void assertBox(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, BuildBox box) {
		Optional<Build> build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, name));
		if (build.isEmpty() || build.get().version() != 1 || !build.get().box().equals(box)) {
			throw new AssertionError("Expected build '" + name + "' v1 with box " + box + " but got " + build.orElse(null));
		}
		context.waitFor(client -> {
			ClientBuild selected = ClientBuilds.selected();
			return selected != null && selected.name().equals(name);
		});
		ClientBuild shown = context.computeOnClient(client -> ClientBuilds.selected());
		if (!shown.box().equals(box)) {
			throw new AssertionError("Expected the client to show box " + box + " for '" + name + "' but got " + shown.box());
		}
	}
}
