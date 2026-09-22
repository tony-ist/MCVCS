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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;

/**
 * {@code /vcs place} shows the copy it is about to put down instead of placing it, and the numpad keys line it up:
 * away and back through the side of the box in sight, sideways along it, up and down, a block at a time or ten with
 * sprint held. Looking at no side of it leaves the sideways keys nothing to go by, so they move nothing and say so.
 * Numpad {@code 5} then places the copy where it was left, as {@code /vcs confirmPlace} does, and says why instead
 * when it stands where it cannot be placed; {@code /vcs cancelPlace} drops it.
 * <p>
 * How far a sprinting press moves it comes from {@code config/mcvcs.json}, which the test writes before joining.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsPlacePreviewGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-place-preview";
	private static final String MOVED = "moved";

	/** What this test puts in the config file, chosen to be neither the default nor a number a bug would land on. */
	private static final int SPRINT_STEP = 4;

	@Override
	protected void run(ClientGameTestContext context) {
		checkKeyBindsScreen(context);
		configureSprintStep(context);

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			assertSprintStep(context);

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

			// Sprint held, a press moves as many blocks as the config file asks for instead of one.
			box = sprintPress(context, PlacePreviewKeys.AWAY, box, 0, 0, SPRINT_STEP);
			box = sprintPress(context, PlacePreviewKeys.CLOSER, box, 0, 0, -SPRINT_STEP);

			// The step follows the sprint binding, not the key sprint happens to have out of the box: rebound to
			// shift, which is what many players do and which sneak is already on, holding shift is what moves ten.
			box = shiftSprintPress(singleplayer, context, viewpoint, PlacePreviewKeys.AWAY, box, 0, 0, SPRINT_STEP);
			box = shiftSprintPress(singleplayer, context, viewpoint, PlacePreviewKeys.CLOSER, box, 0, 0, -SPRINT_STEP);

			// Looking at the sky there is no side of the box in sight, so the sideways keys move nothing and say why.
			context.runOnClient(client -> client.player.setXRot(-90.0f));
			context.waitTicks(2);
			context.getInput().pressKey(PlacePreviewKeys.AWAY);
			context.waitTicks(5);
			assertShowing(context, box);
			assertOverlay(context, PlacePreviewKeys.HINT);
			screenshotLastFrame(context, "mcvcs-vcs-place-preview-no-face");

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
			box = sprintPress(context, PlacePreviewKeys.AWAY, box, 0, 0, SPRINT_STEP);
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
	 * The sprint step comes from {@code config/mcvcs.json}, which the mod writes with its defaults when it finds
	 * none and reads again on every join, so writing it here and joining afterwards is all it takes to change.
	 */
	private static void configureSprintStep(ClientGameTestContext context) {
		Path file = ClientConfig.file();
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("Expected the client to write its defaults to " + file);
		}
		try {
			Files.writeString(file, "{\"sprintStep\": " + SPRINT_STEP + "}");
		} catch (IOException e) {
			throw new AssertionError("Failed to write " + file, e);
		}
	}

	/** The config file is read on join, so this runs once the world is up. */
	private static void assertSprintStep(ClientGameTestContext context) {
		int step = context.computeOnClient(client -> ClientConfig.sprintStep());
		if (step != SPRINT_STEP) {
			throw new AssertionError("Expected a sprint step of " + SPRINT_STEP + " from " + ClientConfig.file() + " but got " + step);
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
				PlacePreviewKeys.RIGHT, PlacePreviewKeys.UP, PlacePreviewKeys.DOWN, PlacePreviewKeys.CONFIRM}) {
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

	/** The same with the sprint key held, which is what makes a press move {@link ClientConfig#sprintStep()} blocks. */
	private static BuildBox sprintPress(ClientGameTestContext context, KeyMapping key, BuildBox box, int x, int y, int z) {
		context.getInput().holdKey(options -> options.keySprint);
		try {
			return press(context, key, box, x, y, z);
		} finally {
			context.getInput().releaseKey(options -> options.keySprint);
		}
	}

	/**
	 * The same again with sprint rebound to left shift and shift itself held down, the way a player who moved the
	 * binding would press it. Shift already carries sneak, so the copy only moves its whole step if sharing a key
	 * with another binding leaves sprint counting as held.
	 * <p>
	 * Sneaking is what a spectator descends with, so the player sinks for as long as shift is down: they are put
	 * back at {@code viewpoint} before the press and again after it, and shift is held only over the press itself,
	 * so what they are looking at is the side of the box both times and not its underside.
	 */
	private static BuildBox shiftSprintPress(TestSingleplayerContext singleplayer, ClientGameTestContext context, BlockPos viewpoint,
			KeyMapping key, BuildBox box, int x, int y, int z) {
		bindSprintToShift(context);
		watchFrom(singleplayer, context, viewpoint);
		aim(context, box);
		context.getInput().holdShift();
		try {
			context.getInput().pressKey(key);
			BuildBox moved = new BuildBox(box.min().offset(x, y, z), box.max().offset(x, y, z));
			assertShowing(context, moved);
			return moved;
		} finally {
			context.getInput().releaseShift();
			// Back to the key it came with, so the rest of this test, and every test after it, presses what it expects.
			context.runOnClient(client -> {
				client.options.keySprint.setKey(client.options.keySprint.getDefaultKey());
				KeyMapping.resetMapping();
			});
			watchFrom(singleplayer, context, viewpoint);
		}
	}

	/** Puts the player back where they watch the copy from, wherever sneaking has carried them. */
	private static void watchFrom(TestSingleplayerContext singleplayer, ClientGameTestContext context, BlockPos viewpoint) {
		teleport(singleplayer, viewpoint);
		context.waitTicks(2);
	}

	/** Puts sprint on left shift, as Options, Controls, Key Binds would. */
	private static void bindSprintToShift(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (!client.options.keySprint.isDefault()) {
				throw new AssertionError("Expected sprint on its default key before rebinding it");
			}
			InputConstants.Key shift = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_LSHIFT);
			if (!shift.equals(client.options.keyShift.getDefaultKey())) {
				throw new AssertionError("Expected sneak on left shift, which is what makes this the conflicting case");
			}
			client.options.keySprint.setKey(shift);
			KeyMapping.resetMapping();
			MCVCS.LOGGER.info("Sprint rebound to {}, which sneak is on as well", client.options.keySprint.getTranslatedKeyMessage().getString());
		});
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
