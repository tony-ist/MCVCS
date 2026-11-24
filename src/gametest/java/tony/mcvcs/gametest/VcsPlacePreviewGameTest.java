package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.create;
import static tony.mcvcs.gametest.VcsTestSupport.blockAt;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.teleport;

import java.util.Arrays;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.place.PlacePreviewBlocks;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.place.PlacePreviewKeys.ScrollAxis;
import tony.mcvcs.client.place.PlacePreviewStatus;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;

/**
 * {@code /vcs place} shows the copy it is about to put down instead of placing it, and the numpad keys line it up:
 * away and back through the side of the box in sight, sideways along it, up and down, a block at a time. Pressing the
 * scroll key picks the axis the mouse wheel moves the copy along, a block per notch, and keeps it while held: through
 * the face in sight, any of the six, or with none in sight (or from inside the box) the axis the player looks along
 * most. The wheel is left alone while that key is not held. With no face in sight the numpad keys go by the face
 * nearest the player, and from inside the box by where the player looks. Numpad {@code 5} then places the copy
 * where it was left, as {@code /vcs confirmPlace} does, and says why instead when it stands where it cannot be
 * placed; {@code /vcs cancelPlace} drops it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsPlacePreviewGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-place-preview";
	private static final String MOVED = "moved";
	private static final String LAYERED_NAME = "gametest-place-preview-layers";

	@Override
	protected void run(ClientGameTestContext context) {
		checkKeyBindsScreen(context);

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getConnection().waitForChunksRender();

			// A copy is shown below the player's feet, where they would otherwise be standing, so the test player
			// spectates: they stay where they are put instead of falling onto it once it is placed.
			runCommand(context, "gamemode spectator");

			// A 3x2x2 stone box in front of and to the right of the player, with a gold block in its top south-east
			// corner, so a copy that has been turned or mirrored would not pass for the original.
			BlockPos start = playerPos(singleplayer);
			BlockPos min = start.offset(2, 1, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), max, Blocks.GOLD_BLOCK.defaultBlockState());
			create(context, singleplayer, BUILD_NAME, min, max);
			read(schematic(BUILD_NAME, 1));

			// The copy is shown one block below the player's feet, extending east and south, and nothing else happens:
			// the world is untouched and the build still has only the placement /vcs create made.
			BlockPos feet = hover(singleplayer, context, start.offset(0, 10, 20));
			BuildBox shown = below(feet);
			runCommand(context, "vcs place " + BUILD_NAME + " latest " + MOVED);
			assertShowing(context, shown);
			// Its blocks are meshed once, away from the client thread, and drawn from then on wherever it stands.
			context.waitFor(client -> PlacePreviewBlocks.drawn());
			// The action bar stays empty while the copy can be placed: how to move and place it is on the hint line.
			assertOverlay(context, "");
			if (blockAt(singleplayer, shown.min()) != Blocks.AIR.defaultBlockState()) {
				throw new AssertionError("A copy that is only shown must leave the world alone but " + shown.min().toShortString()
					+ " holds " + blockAt(singleplayer, shown.min()));
			}
			assertPlacements(singleplayer, List.of(Build.MAIN));

			// Watching the copy from the north, six blocks off and level with it, so its north side is the one in
			// sight and every key has a side to work from. Moving the player never moves the copy.
			BlockPos viewpoint = new BlockPos(shown.min().getX() + 1, shown.min().getY(), shown.min().getZ() - 6);
			teleport(singleplayer, viewpoint);
			context.waitTicks(2);
			BuildBox box = press(context, PlacePreviewKeys.AWAY, shown, 0, 0, 1);
			screenshotLastFrame(context, "mcvcs-vcs-place-preview");

			// The copy is drawn see-through, so it cannot be taken for blocks already there: a gold wall standing behind
			// it shows through it. The chat is cleared to keep it out of the way. The wall goes again afterwards.
			BlockPos wallMin = new BlockPos(box.min().getX() - 2, box.min().getY() - 1, box.max().getZ() + 2);
			BlockPos wallMax = new BlockPos(box.max().getX() + 2, box.max().getY() + 1, box.max().getZ() + 2);
			fillBox(singleplayer, wallMin, wallMax, Blocks.GOLD_BLOCK.defaultBlockState(), wallMin, Blocks.GOLD_BLOCK.defaultBlockState());
			context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
			context.waitTicks(5);
			singleplayer.getConnection().waitForChunksRender();
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-see-through");
			fillBox(singleplayer, wallMin, wallMax, Blocks.AIR.defaultBlockState(), wallMin, Blocks.AIR.defaultBlockState());
			context.waitTicks(2);

			box = press(context, PlacePreviewKeys.CLOSER, box, 0, 0, -1);
			// Looking at the north side, the player faces south, so their left hand points east.
			box = press(context, PlacePreviewKeys.LEFT, box, 1, 0, 0);
			box = press(context, PlacePreviewKeys.RIGHT, box, -1, 0, 0);
			// Up and down need no side to work from, being the same whichever way the player looks.
			box = press(context, PlacePreviewKeys.UP, box, 0, 1, 0);
			box = press(context, PlacePreviewKeys.DOWN, box, 0, -1, 0);

			// The scroll key held, the wheel does what 8 and 2 do: a notch up pushes the copy away, a notch down
			// pulls it back, one block each, whichever way round the wheel is turned.
			box = scroll(context, box, 1.0, 0, 0, 1);
			box = scroll(context, box, -1.0, 0, 0, -1);

			// The wheel takes the top and the bottom of the box as well, which the numpad keys leave alone. Looking
			// down on the copy from above, away from the player is downwards, so a notch up lowers it and a notch
			// down raises it.
			watchFrom(singleplayer, context, new BlockPos(box.min().getX() + 1, box.max().getY() + 6, box.min().getZ()));
			box = scroll(context, box, 1.0, 0, -1, 0);
			box = scroll(context, box, -1.0, 0, 1, 0);

			// And from underneath, looking up at its bottom face, away is upwards instead.
			watchFrom(singleplayer, context, new BlockPos(box.min().getX() + 1, box.min().getY() - 6, box.min().getZ()));
			box = scroll(context, box, 1.0, 0, 1, 0);
			box = scroll(context, box, -1.0, 0, -1, 0);

			// Back to watching it from the north, where every key has a side of the box to work from again.
			watchFrom(singleplayer, context, viewpoint);

			// Pressing the scroll key while looking at the north face keeps that face's axis for as long as it is held,
			// drawn with an arrow out of the box each way, so looking away from the copy still moves it along Z.
			aim(context, box);
			context.getInput().holdKey(PlacePreviewKeys.SCROLL);
			try {
				context.waitTicks(2);
				assertScrollAxis(context, new ScrollAxis(Direction.SOUTH, Direction.NORTH));
				screenshotLastFrame(context, "mcvcs-vcs-place-preview-axis");
				// Walking round to the side keeps the axis, and shows its arrows standing out of both ends of the box.
				teleport(singleplayer, viewpoint.offset(-6, 3, 7));
				context.waitTicks(2);
				aim(context, box);
				assertScrollAxis(context, new ScrollAxis(Direction.SOUTH, Direction.NORTH));
				screenshotLastFrame(context, "mcvcs-vcs-place-preview-axis-side");
				// Far off, the axis and its chevrons grow with the distance, so they are still easy to make out.
				teleport(singleplayer, viewpoint.offset(-30, 12, 36));
				context.waitTicks(2);
				aim(context, box);
				screenshotLastFrame(context, "mcvcs-vcs-place-preview-axis-far");
				context.runOnClient(client -> client.player.setXRot(-90.0f));
				context.waitTicks(2);
				context.getInput().scroll(1.0);
				box = new BuildBox(box.min().offset(0, 0, 1), box.max().offset(0, 0, 1));
				assertShowing(context, box);
			} finally {
				context.getInput().releaseKey(PlacePreviewKeys.SCROLL);
			}
			context.waitTicks(2);
			assertScrollAxis(context, null);
			watchFrom(singleplayer, context, viewpoint);

			// Pressed looking at no face, the axis is the one the player looks along most: a notch up moves the copy
			// the way they look. Turned east and a little up, away from the copy, that is east.
			context.runOnClient(client -> {
				client.player.setYRot(-90.0f);
				client.player.setXRot(-20.0f);
			});
			context.waitTicks(2);
			box = scrollAsIs(context, box, 1.0, 1, 0, 0);
			box = scrollAsIs(context, box, -1.0, -1, 0, 0);
			watchFrom(singleplayer, context, viewpoint);

			// The wheel is left alone while that key is not held, so it goes on doing whatever it usually does.
			aim(context, box);
			context.getInput().scroll(1.0);
			context.waitTicks(5);
			assertShowing(context, box);

			// Sprint has nothing to do with how far a press moves any more: held or not, it is one block.
			box = sprintPress(context, PlacePreviewKeys.AWAY, box, 0, 0, 1);
			box = sprintPress(context, PlacePreviewKeys.CLOSER, box, 0, 0, -1);

			// Looking at the sky there is no face of the box in sight, so the keys go by the face nearest the player
			// instead, the north one, as if they were still looking at it. The wheel goes by where the player looks,
			// up, so a notch down lowers the copy.
			context.runOnClient(client -> client.player.setXRot(-90.0f));
			context.waitTicks(2);
			box = pressAsIs(context, PlacePreviewKeys.AWAY, box, 0, 0, 1);
			box = pressAsIs(context, PlacePreviewKeys.LEFT, box, 1, 0, 0);
			box = scrollAsIs(context, box, -1.0, 0, -1, 0);
			box = pressAsIs(context, PlacePreviewKeys.RIGHT, box, -1, 0, 0);
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-no-face");

			// From inside the box no face is before the player, so the moves go by where they look: away is the way
			// they look, south here, and with the wheel looking down it is downwards. Each move is undone by the next,
			// so the player is still inside the box for every one of them.
			watchFrom(singleplayer, context, box.min());
			box = pressAsIs(context, PlacePreviewKeys.CLOSER, box, 0, 0, -1);
			box = pressAsIs(context, PlacePreviewKeys.AWAY, box, 0, 0, 1);
			box = scrollAsIs(context, box, -1.0, 0, 0, -1);
			box = scrollAsIs(context, box, 1.0, 0, 0, 1);
			context.runOnClient(client -> client.player.setXRot(90.0f));
			context.waitTicks(2);
			box = scrollAsIs(context, box, -1.0, 0, 1, 0);
			box = scrollAsIs(context, box, 1.0, 0, -1, 0);
			// Looking down the sideways keys still go by the way the player is turned: facing south, left is east.
			box = pressAsIs(context, PlacePreviewKeys.RIGHT, box, -1, 0, 0);
			box = pressAsIs(context, PlacePreviewKeys.LEFT, box, 1, 0, 0);
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-inside");
			watchFrom(singleplayer, context, viewpoint);

			// A copy standing over blocks would overwrite them, which is refused without -f, and the box says so by
			// turning red before the command is ever run. A stone slab is laid right below where the copy stands.
			BuildBox under = new BuildBox(box.min().below(), new BlockPos(box.max().getX(), box.min().getY() - 1, box.max().getZ()));
			fillBox(singleplayer, under.min(), under.max(), Blocks.STONE.defaultBlockState(), under.min(), Blocks.STONE.defaultBlockState());
			aim(context, box);
			box = press(context, PlacePreviewKeys.DOWN, box, 0, -1, 0);
			assertOverlay(context, "6 blocks in the way");
			lookAt(context, box.min(), box.max().above(2));

			// Asking to place it there says why instead, and leaves both the world and the copy as they are.
			context.getInput().pressKey(PlacePreviewKeys.CONFIRM);
			context.waitTicks(5);
			assertOverlay(context, PlacePreviewKeys.REFUSED + "6 blocks in the way");
			assertShowing(context, box);
			assertPlacements(singleplayer, List.of(Build.MAIN));
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-refused");

			box = press(context, PlacePreviewKeys.UP, box, 0, 1, 0);
			assertPlaceable(context, true);

			// Blocks built into the copy while it stands still turn it red too, and it goes green again once they go.
			BlockPos inside = box.min();
			fillBox(singleplayer, inside, inside, Blocks.STONE.defaultBlockState(), inside, Blocks.STONE.defaultBlockState());
			assertPlaceable(context, false);
			fillBox(singleplayer, inside, inside, Blocks.AIR.defaultBlockState(), inside, Blocks.AIR.defaultBlockState());
			assertPlaceable(context, true);

			// Left where it is not shown, so that placing it proves the server followed the copy about rather than
			// putting it back where /vcs place first showed it.
			box = press(context, PlacePreviewKeys.LEFT, box, 1, 0, 0);
			box = scroll(context, box, 1.0, 0, 0, 1);
			if (box.equals(shown)) {
				throw new AssertionError("The copy was meant to end up somewhere other than " + shown);
			}

			// Numpad 5 puts the copy where it was left, gold corner and all, and stops showing it. The last move is
			// given a moment to reach the server first: the client draws the copy in its new place before the server
			// has been told, and confirming inside that window has been seen to place the copy a block short.
			context.waitTicks(5);
			BuildBox placed = box;
			context.getInput().pressKey(PlacePreviewKeys.CONFIRM);
			context.waitTicks(10);
			if (blockAt(singleplayer, placed.max()) != Blocks.GOLD_BLOCK.defaultBlockState()
				|| blockAt(singleplayer, placed.min()) != Blocks.STONE.defaultBlockState()) {
				throw new AssertionError("Expected the copy at " + placed + " but found " + blockAt(singleplayer, placed.min())
					+ " and " + blockAt(singleplayer, placed.max()));
			}
			assertPlacements(singleplayer, List.of(Build.MAIN, MOVED));
			assertBox(singleplayer, MOVED, placed);
			context.waitFor(client -> PreviewManager.place() == null);
			context.waitFor(client -> {
				var selected = ClientPlacements.selected();
				return selected != null && selected.label().equals(BUILD_NAME + "/" + MOVED);
			});
			lookAt(context, placed.min(), placed.max().above(2));
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-placed");

			// Cancelling drops the copy without placing anything.
			BuildBox cancelled = below(hover(singleplayer, context, start.offset(0, 10, 60)));
			runCommand(context, "vcs place " + BUILD_NAME);
			assertShowing(context, cancelled);
			runCommand(context, "vcs cancelPlace");
			context.waitFor(client -> PreviewManager.place() == null);
			if (blockAt(singleplayer, cancelled.min()) != Blocks.AIR.defaultBlockState()) {
				throw new AssertionError("A cancelled copy must leave the world alone but " + cancelled.min().toShortString()
					+ " holds " + blockAt(singleplayer, cancelled.min()));
			}
			assertPlacements(singleplayer, List.of(Build.MAIN, MOVED));

			// And the keys do nothing at all once no copy is being shown.
			context.getInput().pressKey(PlacePreviewKeys.UP);
			context.waitTicks(5);
			if (PreviewManager.place() != null) {
				throw new AssertionError("Expected no copy to be shown after cancelling");
			}

			checkTooHigh(context, singleplayer, start);
			checkLayeredCopy(context, singleplayer, start);
		}
	}

	/**
	 * A copy sticking out above the world's build height cannot be placed: the box turns red, says the build is too
	 * high, and Numpad 5 refuses. Shown with its top on the highest layer, it is fine until raised one block.
	 */
	private static void checkTooHigh(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos start) {
		int maxY = context.computeOnClient(client -> client.level.getMaxY());
		BlockPos feet = hover(singleplayer, context, new BlockPos(start.getX(), maxY + 1, start.getZ() + 100));
		BuildBox box = below(feet);
		if (box.max().getY() != maxY) {
			throw new AssertionError("Expected the copy's top at y " + maxY + " but it is " + box);
		}
		runCommand(context, "vcs place " + BUILD_NAME);
		assertShowing(context, box);
		assertPlaceable(context, true);

		watchFrom(singleplayer, context, new BlockPos(box.min().getX() + 1, box.min().getY(), box.min().getZ() - 6));
		box = press(context, PlacePreviewKeys.UP, box, 0, 1, 0);
		assertPlaceable(context, false);
		assertOverlay(context, PlacePreviewStatus.TOO_HIGH);

		context.getInput().pressKey(PlacePreviewKeys.CONFIRM);
		context.waitTicks(5);
		assertOverlay(context, PlacePreviewKeys.REFUSED + PlacePreviewStatus.TOO_HIGH);
		assertShowing(context, box);
		assertPlacements(singleplayer, List.of(Build.MAIN, MOVED));
		context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
		context.waitTicks(2);
		screenshotLastFrame(context, "mcvcs-vcs-place-preview-too-high");

		// The server refuses it too, should the command be typed.
		runCommand(context, "vcs confirmPlace");
		context.waitTicks(5);
		assertShowing(context, box);
		assertPlacements(singleplayer, List.of(Build.MAIN, MOVED));

		box = press(context, PlacePreviewKeys.DOWN, box, 0, -1, 0);
		assertPlaceable(context, true);
		runCommand(context, "vcs cancelPlace");
		context.waitFor(client -> PreviewManager.place() == null);
	}

	/**
	 * Only the nearest surface of a copy is drawn, faded over the world: a copy with floors one above another shows its
	 * top floor, not every floor below it blended together. A gold floor under two stone ones, with gold pillars between
	 * them, seen from above shows a plain stone top floor, with no yellow showing through it.
	 */
	private static void checkLayeredCopy(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos start) {
		BlockPos min = start.offset(20, 1, 0);
		BlockPos max = min.offset(8, 6, 8);
		fillBox(singleplayer, min, max, Blocks.AIR.defaultBlockState(), min, Blocks.AIR.defaultBlockState());
		// The bottom floor is gold, so any of it showing through the stone floors above would tint them yellow.
		for (int y = 0; y <= 6; y += 3) {
			BlockState floor = y == 0 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
			fillBox(singleplayer, min.offset(0, y, 0), new BlockPos(max.getX(), min.getY() + y, max.getZ()), floor, min.offset(0, y, 0), floor);
		}
		for (BlockPos corner : new BlockPos[]{min.offset(1, 1, 1), min.offset(7, 1, 1), min.offset(1, 1, 7), min.offset(7, 1, 7)}) {
			fillBox(singleplayer, corner, corner.offset(0, 4, 0), Blocks.GOLD_BLOCK.defaultBlockState(), corner, Blocks.GOLD_BLOCK.defaultBlockState());
		}
		create(context, singleplayer, LAYERED_NAME, min, max);
		read(schematic(LAYERED_NAME, 1));

		// The copy hangs below the player's feet, extending east and south, like the small one above.
		BlockPos feet = hover(singleplayer, context, start.offset(20, 20, 40));
		BuildBox shown = new BuildBox(feet.offset(0, -7, 0), feet.offset(8, -1, 8));
		fillBox(singleplayer, shown.min(), shown.max(), Blocks.AIR.defaultBlockState(), shown.min(), Blocks.AIR.defaultBlockState());
		runCommand(context, "vcs place " + LAYERED_NAME);
		assertShowing(context, shown);
		context.waitFor(client -> PlacePreviewBlocks.drawn());

		teleport(singleplayer, shown.min().offset(-2, 13, -2));
		context.waitTicks(2);
		lookAt(context, shown.min(), shown.max());
		context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
		context.waitTicks(5);
		screenshotLastFrame(context, "mcvcs-vcs-place-preview-layered");

		runCommand(context, "vcs cancelPlace");
		context.waitFor(client -> PreviewManager.place() == null);
	}

	/**
	 * Every key is one of the game's own key mappings, so they can all be rebound under Options, Controls, Key Binds,
	 * where they and their category have readable names. Mod categories are listed after vanilla's, so the list is
	 * scrolled to the end for the screenshot.
	 */
	private static void checkKeyBindsScreen(ClientGameTestContext context) {
		context.runOnClient(client -> {
			for (KeyMapping key : new KeyMapping[] {PlacePreviewKeys.AWAY, PlacePreviewKeys.CLOSER, PlacePreviewKeys.LEFT,
				PlacePreviewKeys.RIGHT, PlacePreviewKeys.UP, PlacePreviewKeys.DOWN, PlacePreviewKeys.CONFIRM,
				PlacePreviewKeys.SCROLL}) {
				if (Arrays.stream(client.options.keyMappings).noneMatch(mapping -> mapping == key)) {
					throw new AssertionError("Expected " + key.getName() + " among the game's key mappings");
				}
				if (!Language.getInstance().has(key.getName())) {
					throw new AssertionError("Expected a translation for " + key.getName());
				}
			}
		});

		context.setScreen(() -> new KeyBindsScreen(null, Minecraft.getInstance().options));
		context.waitForScreen(KeyBindsScreen.class);
		context.runOnClient(client -> {
			KeyBindsList list = client.gui.screen().children().stream()
				.filter(KeyBindsList.class::isInstance).map(KeyBindsList.class::cast).findFirst()
				.orElseThrow(() -> new AssertionError("Expected a key binds list on " + client.gui.screen()));
			list.setScrollAmount(list.maxScrollAmount());
		});
		context.waitTicks(2);
		context.takeScreenshot("mcvcs-vcs-place-preview-key-binds");

		context.setScreen(TitleScreen::new);
		context.waitForScreen(TitleScreen.class);
	}

	/**
	 * Looks at the copy, presses {@code key} and checks it moved by {@code (x, y, z)} blocks. Aiming afresh every
	 * time keeps the same side of the box in sight as it moves away from the player.
	 */
	private static BuildBox press(ClientGameTestContext context, KeyMapping key, BuildBox box, int x, int y, int z) {
		aim(context, box);
		return pressAsIs(context, key, box, x, y, z);
	}

	/** {@link #press} without turning to the copy first, so the player goes on looking wherever they were. */
	private static BuildBox pressAsIs(ClientGameTestContext context, KeyMapping key, BuildBox box, int x, int y, int z) {
		context.getInput().pressKey(key);
		BuildBox moved = new BuildBox(box.min().offset(x, y, z), box.max().offset(x, y, z));
		assertShowing(context, moved);
		// A step only moves where the mesh is drawn: the copy's blocks are there at the step itself, never meshed again.
		if (!context.computeOnClient(client -> PlacePreviewBlocks.drawn())) {
			throw new AssertionError("Expected the copy's blocks to be drawn right after a step to " + moved);
		}
		return moved;
	}

	/**
	 * Looks at the copy, turns the mouse wheel by {@code amount} notches with {@link PlacePreviewKeys#SCROLL} held,
	 * and checks it moved by {@code (x, y, z)} blocks.
	 */
	private static BuildBox scroll(ClientGameTestContext context, BuildBox box, double amount, int x, int y, int z) {
		aim(context, box);
		return scrollAsIs(context, box, amount, x, y, z);
	}

	/** {@link #scroll} without turning to the copy first, so the player goes on looking wherever they were. */
	private static BuildBox scrollAsIs(ClientGameTestContext context, BuildBox box, double amount, int x, int y, int z) {
		context.getInput().holdKey(PlacePreviewKeys.SCROLL);
		try {
			context.getInput().scroll(amount);
			BuildBox moved = new BuildBox(box.min().offset(x, y, z), box.max().offset(x, y, z));
			assertShowing(context, moved);
			return moved;
		} finally {
			context.getInput().releaseKey(PlacePreviewKeys.SCROLL);
		}
	}

	/** Puts the player where they watch the copy from, so the face of the box in sight is the one a step wants. */
	private static void watchFrom(TestSingleplayerContext singleplayer, ClientGameTestContext context, BlockPos viewpoint) {
		teleport(singleplayer, viewpoint);
		context.waitTicks(2);
	}

	/** The same as {@link #press} with the sprint key held, which no longer makes any difference to how far it goes. */
	private static BuildBox sprintPress(ClientGameTestContext context, KeyMapping key, BuildBox box, int x, int y, int z) {
		context.getInput().holdKey(options -> options.keySprint);
		try {
			return press(context, key, box, x, y, z);
		} finally {
			context.getInput().releaseKey(options -> options.keySprint);
		}
	}

	/** Turns the player to the middle of the copy, which from where they stand means its north side. */
	private static void aim(ClientGameTestContext context, BuildBox box) {
		lookAt(context, box.min(), box.max());
	}

	/** Waits until the client is showing a copy at {@code box}, and says what it is showing instead if it never does. */
	private static void assertShowing(ClientGameTestContext context, BuildBox box) {
		try {
			context.waitFor(client -> {
				PlacePreview preview = PreviewManager.place();
				return preview != null && preview.box().equals(box);
			});
		} catch (RuntimeException | AssertionError e) {
			PlacePreview preview = context.computeOnClient(client -> PreviewManager.place());
			throw new AssertionError("Expected a copy shown at " + box + " but got " + (preview == null ? "none" : preview.box()), e);
		}
	}

	/** Waits until the copy is drawn green, or red, as {@code expected} says. */
	private static void assertPlaceable(ClientGameTestContext context, boolean expected) {
		try {
			context.waitFor(client -> {
				PlacePreviewStatus.Status status = PlacePreviewStatus.status();
				return status != null && status.placeable() == expected;
			});
		} catch (RuntimeException | AssertionError e) {
			throw new AssertionError("Expected the copy to be " + (expected ? "placeable" : "refused") + " but it is "
				+ context.computeOnClient(client -> PlacePreviewStatus.status()), e);
		}
	}

	/** The axis the wheel moves the copy along is {@code expected}, null meaning none is picked. */
	private static void assertScrollAxis(ClientGameTestContext context, ScrollAxis expected) {
		ScrollAxis actual = context.computeOnClient(client -> PlacePreviewKeys.scrollAxis());
		if (!java.util.Objects.equals(actual, expected)) {
			throw new AssertionError("Expected the scroll axis " + expected + " but it is " + actual);
		}
	}

	/** The action bar is showing {@code expected}. */
	private static void assertOverlay(ClientGameTestContext context, String expected) {
		String message = context.computeOnClient(VcsTestSupport::overlayMessage);
		if (message == null || !message.equals(expected)) {
			throw new AssertionError("Expected the action bar to say '" + expected + "' but it says '" + message + "'");
		}
	}

	/**
	 * Puts the spectating player at {@code pos} and empties the box a copy would be shown in below them, so nothing
	 * but what a test puts there is ever in the way. Returns where their feet really ended up.
	 */
	private static BlockPos hover(TestSingleplayerContext singleplayer, ClientGameTestContext context, BlockPos pos) {
		teleport(singleplayer, pos);
		context.waitTicks(2);
		BlockPos feet = playerPos(singleplayer);
		BuildBox box = below(feet);
		fillBox(singleplayer, box.min(), box.max(), Blocks.AIR.defaultBlockState(), box.min(), Blocks.AIR.defaultBlockState());
		return feet;
	}

	/** Where a 3x2x2 copy is shown for a player whose feet are at {@code feet}: below them, extending east and south. */
	private static BuildBox below(BlockPos feet) {
		return new BuildBox(feet.offset(0, -2, 0), feet.offset(2, -1, 1));
	}

	/** The build's placements on disk, by name, in order. */
	private static void assertPlacements(TestSingleplayerContext singleplayer, List<String> expected) {
		List<String> actual = singleplayer.getServer().computeOnServer(server ->
			BuildRegistry.find(server, BUILD_NAME).map(Build::placementNames).orElse(List.of()));
		if (!actual.equals(expected)) {
			throw new AssertionError("Expected placements " + expected + " but got " + actual);
		}
	}

	/** The placement called {@code name} covers {@code box}, which is where the copy was left before confirming. */
	private static void assertBox(TestSingleplayerContext singleplayer, String name, BuildBox box) {
		BuildBox actual = singleplayer.getServer().computeOnServer(server ->
			BuildRegistry.find(server, BUILD_NAME).flatMap(build -> BuildPlacement.of(build, name)).map(BuildPlacement::box).orElse(null));
		if (!box.equals(actual)) {
			throw new AssertionError("Expected placement '" + name + "' at " + box + " but it covers " + actual);
		}
	}
}
