package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.blockAt;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.build.Schematic;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.selection.PendingClickHighlight;

/**
 * {@code /vcs create <buildname> [placementname] -b} waits for two clicks, a punch or an empty-handed right-click
 * each, and makes the build exactly the box between them: it does not grow over what touches the box, the corners can
 * be clicked in either order, and a box that would overlap another build is refused.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsCreateBoxGameTest extends VcsGameTest {
	private static final String BOX_NAME = "gametest-box";
	private static final String REVERSED_NAME = "gametest-box-reversed";
	private static final String REVERSED_PLACEMENT = "rig";
	private static final String OVERLAPPING_NAME = "gametest-box-overlap";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) RECEIVED.add(message); });
	}

	@Override
	protected void run(ClientGameTestContext context) {
		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Two hovering corner markers in front of and to the right of the player, within reach, a stone inside the
			// box between them, and an emerald touching the diamond from outside the box, which a grown box would take in.
			BlockPos player = playerPos(singleplayer);
			BlockPos gold = player.offset(2, 1, 2);
			BlockPos diamond = player.offset(3, 3, 3);
			BlockPos stone = player.offset(3, 1, 3);
			BlockPos emerald = diamond.offset(1, 0, 0);
			setBlock(singleplayer, gold, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, diamond, Blocks.DIAMOND_BLOCK.defaultBlockState());
			setBlock(singleplayer, stone, Blocks.STONE.defaultBlockState());
			setBlock(singleplayer, emerald, Blocks.EMERALD_BLOCK.defaultBlockState());
			BuildBox box = new BuildBox(gold, diamond);

			// Nothing is created yet; the player is asked for the first corner.
			assertOnlyMessage(run(context, "vcs create " + BOX_NAME + " -b"), "Hit two opposite corners, or right-click them with an empty hand, to save everything between them as " + BOX_NAME);
			assertArmed(context, "Hit one corner of " + BOX_NAME, null);

			// The first punch says nothing in chat and asks for the second corner, outlining the box so far.
			lookAt(context, gold, gold);
			assertNoMessage(click(context, options -> options.keyAttack));
			assertArmed(context, "Now hit the opposite corner of " + BOX_NAME, gold);
			assertNoBuild(BOX_NAME);
			assertBlockAt(singleplayer, gold, Blocks.GOLD_BLOCK);
			lookAt(context, diamond, diamond);
			screenshotLastFrame(context, "mcvcs-vcs-create-box-armed");

			// The second punch creates the build from exactly the box between the two, leaving out the emerald.
			List<Component> created = click(context, options -> options.keyAttack);
			assertOnlyMessage(created, "Created build " + BOX_NAME + " v1 (2x3x2, 12 blocks)");
			assertDisarmed(context);
			assertBox(context, singleplayer, BOX_NAME, Build.MAIN, box);
			assertBlockAt(singleplayer, diamond, Blocks.DIAMOND_BLOCK);
			Schematic schematic = read(schematic(BOX_NAME, 1));
			assertSize(schematic, new Vec3i(2, 3, 2));
			assertBlock(schematic, gold, Blocks.GOLD_BLOCK);
			assertBlock(schematic, diamond, Blocks.DIAMOND_BLOCK);
			assertBlock(schematic, stone, Blocks.STONE);
			assertBlock(schematic, gold.offset(0, 1, 0), Blocks.AIR);
			screenshotLastFrame(context, "mcvcs-vcs-create-box");

			// To the player's left, with a placement name, by right-clicking the top corner first and the bottom one
			// second: the box is the same whichever corner comes first.
			BlockPos bottom = player.offset(-2, 1, 2);
			BlockPos top = player.offset(-3, 3, 3);
			setBlock(singleplayer, bottom, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, top, Blocks.DIAMOND_BLOCK.defaultBlockState());
			run(context, "vcs create " + REVERSED_NAME + " " + REVERSED_PLACEMENT + " -b");
			lookAt(context, top, top);
			assertNoMessage(click(context, options -> options.keyUse));
			assertArmed(context, "Now hit the opposite corner of " + REVERSED_NAME, top);
			lookAt(context, bottom, bottom);
			assertOnlyMessage(click(context, options -> options.keyUse), "Created build " + REVERSED_NAME + " v1 (2x3x2, 12 blocks)");
			assertBox(context, singleplayer, REVERSED_NAME, REVERSED_PLACEMENT, new BuildBox(new BlockPos(top.getX(), bottom.getY(), bottom.getZ()), new BlockPos(bottom.getX(), top.getY(), top.getZ())));

			// Two corners inside the first build make a box that overlaps it, which is refused.
			run(context, "vcs create " + OVERLAPPING_NAME + " -b");
			lookAt(context, gold, gold);
			assertNoMessage(click(context, options -> options.keyAttack));
			lookAt(context, diamond, diamond);
			assertOnlyMessage(click(context, options -> options.keyAttack), "Build " + OVERLAPPING_NAME + " would overlap " + BOX_NAME + "/" + Build.MAIN + "; copies can't overlap");
			assertNoBuild(OVERLAPPING_NAME);
			assertDisarmed(context);
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		VcsTestSupport.runCommand(context, command);
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

	/** The action bar shows {@code hint}, and the client is outlining from {@code corner}, or from the block in sight if null. */
	private static void assertArmed(ClientGameTestContext context, String hint, BlockPos corner) {
		context.waitFor(client -> PendingClickHighlight.armed() && Objects.equals(PendingClickHighlight.corner(), corner));
		String message = context.computeOnClient(VcsTestSupport::overlayMessage);
		if (!hint.equals(message)) {
			throw new AssertionError("Expected the action bar to say '" + hint + "' but it says '" + message + "'");
		}
	}

	/** The hint is off the action bar and the client no longer outlines anything. */
	private static void assertDisarmed(ClientGameTestContext context) {
		if (context.computeOnClient(client -> PendingClickHighlight.armed() || PendingClickHighlight.corner() != null)) {
			throw new AssertionError("Expected the client to stop outlining once the second corner was clicked");
		}
		String message = context.computeOnClient(VcsTestSupport::overlayMessage);
		if (message != null && !message.isEmpty()) {
			throw new AssertionError("Expected the action bar to be cleared but it says '" + message + "'");
		}
	}

	private static void assertNoBuild(String name) {
		if (Files.exists(BuildStorage.directory(name))) {
			throw new AssertionError("Expected no build folder " + BuildStorage.directory(name));
		}
	}

	private static void assertBlockAt(TestSingleplayerContext singleplayer, BlockPos pos, Block expected) {
		if (!blockAt(singleplayer, pos).is(expected)) {
			throw new AssertionError("Expected " + expected + " at " + pos + " but the server has " + blockAt(singleplayer, pos));
		}
	}

	/** The build on disk is at v1 with {@code placement} covering {@code box} and, once the server has told it, the client has it selected. */
	private static void assertBox(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, String placement, BuildBox box) {
		Optional<Build> build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, name));
		Optional<BuildBox> actual = build.flatMap(found -> BuildPlacement.of(found, placement)).map(BuildPlacement::box);
		if (build.isEmpty() || build.get().version() != 1 || !actual.equals(Optional.of(box))) {
			throw new AssertionError("Expected build '" + name + "' v1 with " + placement + " covering " + box + " but got " + build.orElse(null));
		}
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name) && selected.placement().equals(placement);
		});
		ClientPlacement shown = context.computeOnClient(client -> ClientPlacements.selected());
		if (!shown.box().equals(box)) {
			throw new AssertionError("Expected the client to show box " + box + " for '" + name + "' but got " + shown.box());
		}
	}
}
