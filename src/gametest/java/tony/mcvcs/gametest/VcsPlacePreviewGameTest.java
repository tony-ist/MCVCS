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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;

/**
 * {@code /vcs place} shows the copy it is about to put down instead of placing it, and the numpad keys line it up:
 * away and back through the side of the box in sight, sideways along it, up and down, a block at a time. Holding the
 * scroll key turns the mouse wheel into the away and back keys, a block per notch, and leaves the wheel alone the
 * rest of the time. Looking at no side of it leaves the sideways keys nothing to go by, so they move nothing and say
 * so. Numpad {@code 5} then places the copy where it was left, as {@code /vcs confirmPlace} does, and says why
 * instead when it stands where it cannot be placed; {@code /vcs cancelPlace} drops it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsPlacePreviewGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-place-preview";
	private static final String MOVED = "moved";

	@Override
	protected void run(ClientGameTestContext context) {
		checkKeyBindsScreen(context);

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A copy is shown below the player's feet, where they would otherwise be standing, so the test player
			// spectates: they stay where they are put instead of falling onto it once it is placed.
			runCommand(context, "gamemode spectator");

			// A 3x2x2 stone box in front of and to the right of the player, with a gold block in its top south-east
			// corner, so a copy that has been turned or mirrored would not pass for the original.
			BlockPos start = playerPos(singleplayer);
			BlockPos min = start.offset(2, 1, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), max, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			read(schematic(BUILD_NAME, 1));

			// The copy is shown one block below the player's feet, extending east and south, and nothing else happens:
			// the world is untouched and the build still has only the placement /vcs create made.
			BlockPos feet = hover(singleplayer, context, start.offset(0, 10, 20));
			BuildBox shown = below(feet);
			runCommand(context, "vcs place " + BUILD_NAME + " latest " + MOVED);
			assertShowing(context, shown);
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

			// The wheel is left alone while that key is not held, so it goes on doing whatever it usually does.
			aim(context, box);
			context.getInput().scroll(1.0);
			context.waitTicks(5);
			assertShowing(context, box);

			// Sprint has nothing to do with how far a press moves any more: held or not, it is one block.
			box = sprintPress(context, PlacePreviewKeys.AWAY, box, 0, 0, 1);
			box = sprintPress(context, PlacePreviewKeys.CLOSER, box, 0, 0, -1);

			// Looking at the sky there is no side of the box in sight, so the sideways keys move nothing and say why.
			context.runOnClient(client -> client.player.setXRot(-90.0f));
			context.waitTicks(2);
			context.getInput().pressKey(PlacePreviewKeys.AWAY);
			context.waitTicks(5);
			assertShowing(context, box);
			assertOverlay(context, PlacePreviewKeys.HINT);
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-no-face");

			// The wheel goes by the same side of the box, so with none in sight it too moves nothing and says why.
			// The action bar is wiped first, since the press just before it left the very message being looked for.
			context.runOnClient(client -> client.gui.setOverlayMessage(Component.empty(), false));
			context.getInput().holdKey(PlacePreviewKeys.SCROLL);
			try {
				context.getInput().scroll(1.0);
				context.waitTicks(5);
			} finally {
				context.getInput().releaseKey(PlacePreviewKeys.SCROLL);
			}
			assertShowing(context, box);
			assertOverlay(context, PlacePreviewKeys.HINT);

			// A copy standing over blocks would overwrite them, which is refused without -f, and the box says so by
			// turning red before the command is ever run. A stone slab is laid right below where the copy stands.
			BuildBox under = new BuildBox(box.min().below(), new BlockPos(box.max().getX(), box.min().getY() - 1, box.max().getZ()));
			fillBox(singleplayer, under.min(), under.max(), Blocks.STONE.defaultBlockState(), under.min(), Blocks.STONE.defaultBlockState());
			aim(context, box);
			box = press(context, PlacePreviewKeys.DOWN, box, 0, -1, 0);
			lookAt(context, box.min(), box.max().above(2));

			// Asking to place it there says why instead, and leaves both the world and the copy as they are.
			context.getInput().pressKey(PlacePreviewKeys.CONFIRM);
			context.waitTicks(5);
			assertOverlay(context, PlacePreviewKeys.REFUSED + "6 blocks in the way");
			assertShowing(context, box);
			assertPlacements(singleplayer, List.of(Build.MAIN));
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-refused");

			box = press(context, PlacePreviewKeys.UP, box, 0, 1, 0);

			// Left where it is not shown, so that placing it proves the server followed the copy about rather than
			// putting it back where /vcs place first showed it.
			box = press(context, PlacePreviewKeys.LEFT, box, 1, 0, 0);
			box = scroll(context, box, 1.0, 0, 0, 1);
			if (box.equals(shown)) {
				throw new AssertionError("The copy was meant to end up somewhere other than " + shown);
			}

			// Numpad 5 puts the copy where it was left, gold corner and all, and stops showing it.
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
		}
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
				if (!I18n.exists(key.getName())) {
					throw new AssertionError("Expected a translation for " + key.getName());
				}
			}
		});

		context.setScreen(() -> new KeyBindsScreen(null, Minecraft.getInstance().options));
		context.waitForScreen(KeyBindsScreen.class);
		context.runOnClient(client -> {
			KeyBindsList list = client.screen.children().stream()
				.filter(KeyBindsList.class::isInstance).map(KeyBindsList.class::cast).findFirst()
				.orElseThrow(() -> new AssertionError("Expected a key binds list on " + client.screen));
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
		context.getInput().pressKey(key);
		BuildBox moved = new BuildBox(box.min().offset(x, y, z), box.max().offset(x, y, z));
		assertShowing(context, moved);
		return moved;
	}

	/**
	 * Looks at the copy, turns the mouse wheel by {@code amount} notches with {@link PlacePreviewKeys#SCROLL} held,
	 * and checks it moved by {@code (x, y, z)} blocks.
	 */
	private static BuildBox scroll(ClientGameTestContext context, BuildBox box, double amount, int x, int y, int z) {
		aim(context, box);
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
		} catch (RuntimeException e) {
			PlacePreview preview = context.computeOnClient(client -> PreviewManager.place());
			throw new AssertionError("Expected a copy shown at " + box + " but got " + (preview == null ? "none" : preview.box()), e);
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
