package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.create;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.mainBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.teleport;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.resize.ClientResize;

/**
 * {@code /vcs resize} lets the player drag the face of the selected box they look at with left alt and the mouse
 * wheel: a notch up pushes it away, a notch down pulls it closer, and the box never gets thinner than a block. The face
 * is picked as alt goes down and kept while it is held; looking at no face, it goes by the axis the player looks along
 * most. A box
 * that overlaps another placement turns red, says so, and is refused by Numpad 5 and {@code /vcs confirmResize}
 * alike; once clear, Numpad 5 commits it as the next version. {@code /vcs cancelResize} drops it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsResizeCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-resize";
	private static final String NEIGHBOUR_NAME = "gametest-resize-neighbour";
	private static final String LABEL = BUILD_NAME + "/" + Build.MAIN;
	private static final String NEIGHBOUR_LABEL = NEIGHBOUR_NAME + "/" + Build.MAIN;

	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) RECEIVED.add(message); });
	}

	@Override
	protected void run(ClientGameTestContext context) {
		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			// Spectating, the player stays where they are put and the wheel has nothing else to do.
			runCommand(context, "gamemode spectator");

			// Nothing to confirm or cancel before a resize has started.
			assertOnlyMessage(run(context, "vcs confirmResize"), "Nothing to resize");
			assertOnlyMessage(run(context, "vcs cancelResize"), "Nothing to cancel");

			// A 2x2x2 stone cube hovering in the air, and a neighbour north of it with a one-block gap between them,
			// so dragging the cube's north face two blocks out reaches into it.
			BlockPos min = playerPos(singleplayer).offset(2, 3, 12);
			BlockPos max = min.offset(1, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			BlockPos neighbourMin = min.offset(0, 0, -3);
			BlockPos neighbourMax = neighbourMin.offset(1, 1, 1);
			fillBox(singleplayer, neighbourMin, neighbourMax, Blocks.GOLD_BLOCK.defaultBlockState(), neighbourMin, Blocks.GOLD_BLOCK.defaultBlockState());
			create(context, singleplayer, NEIGHBOUR_NAME, neighbourMin, neighbourMax);
			create(context, singleplayer, BUILD_NAME, min, max);

			BuildBox box = new BuildBox(min, max);
			assertOnlyMessage(run(context, "vcs resize"), "Resizing " + LABEL + " (2x2x2)");
			assertResizing(context, box);
			assertOverlay(context, "2x2x2 (8 blocks)");

			// Watching from the west, level with the cube: its west face is in sight. A notch down pulls it closer,
			// a notch up pushes it back.
			teleport(singleplayer, new BlockPos(min.getX() - 6, min.getY(), min.getZ()));
			context.waitTicks(2);
			box = scroll(context, box, -1.0, new BuildBox(min.offset(-1, 0, 0), max));
			assertOverlay(context, "3x2x2 (12 blocks)");
			box = scroll(context, box, 1.0, new BuildBox(min, max));
			// Pushing it further in leaves a box one block thick, and the next notch is refused.
			box = scroll(context, box, 1.0, new BuildBox(min.offset(1, 0, 0), max));
			scroll(context, box, 1.0, box);
			assertOverlay(context, "Can't make it any smaller");
			box = scroll(context, box, -1.0, new BuildBox(min, max));

			// From above the top face is in sight; a notch down pulls it up towards the player.
			teleport(singleplayer, new BlockPos(min.getX(), max.getY() + 6, min.getZ()));
			context.waitTicks(2);
			box = scroll(context, box, -1.0, new BuildBox(min, max.offset(0, 1, 0)));

			// From the south, the north face is out of sight and the south face is the one dragged; pulling it closer
			// grows the box south.
			teleport(singleplayer, new BlockPos(min.getX(), min.getY(), max.getZ() + 6));
			context.waitTicks(2);
			box = scroll(context, box, -1.0, new BuildBox(min, max.offset(0, 1, 1)));
			lookAt(context, box.min(), box.max());
			context.runOnClient(client -> client.gui.getChat().clearMessages(false));
			context.waitTicks(2);
			screenshotLastFrame(context, "mcvcs-vcs-resize");

			// Pressing the scroll key while looking at the south face keeps that face for as long as it is held, drawn
			// with its axis, so turning to the sky still drags it.
			context.getInput().holdKey(PlacePreviewKeys.SCROLL);
			try {
				context.waitTicks(2);
				assertTarget(context, new ClientResize.Target(Direction.SOUTH, Direction.NORTH));
				screenshotLastFrame(context, "mcvcs-vcs-resize-axis");
				context.runOnClient(client -> client.player.setXRot(-90.0f));
				context.waitTicks(2);
				context.getInput().scroll(-1.0);
				assertResizing(context, new BuildBox(min, max.offset(0, 1, 2)));
				context.getInput().scroll(1.0);
				assertResizing(context, box);
			} finally {
				context.getInput().releaseKey(PlacePreviewKeys.SCROLL);
			}
			context.waitTicks(2);
			assertTarget(context, null);

			// Pressed looking at no face, it goes by the axis the player looks along most. Turned east and a little
			// up, away from the box, that is the west face, the one towards them, and a notch up pushes it east.
			context.runOnClient(client -> {
				client.player.setYRot(-90.0f);
				client.player.setXRot(-20.0f);
			});
			context.waitTicks(2);
			box = scrollAsIs(context, 1.0, new BuildBox(min.offset(1, 0, 0), max.offset(0, 1, 1)));
			box = scrollAsIs(context, -1.0, new BuildBox(min, max.offset(0, 1, 1)));

			// From the north, two notches pull the north face into the neighbour: the box turns red and says so.
			teleport(singleplayer, new BlockPos(min.getX(), min.getY(), min.getZ() - 8));
			context.waitTicks(2);
			box = scroll(context, box, -1.0, new BuildBox(min.offset(0, 0, -1), max.offset(0, 1, 1)));
			box = scroll(context, box, -1.0, new BuildBox(min.offset(0, 0, -2), max.offset(0, 1, 1)));
			assertOverlay(context, "overlaps " + NEIGHBOUR_LABEL);
			if (!("overlaps " + NEIGHBOUR_LABEL).equals(context.computeOnClient(client -> ClientResize.reason()))) {
				throw new AssertionError("Expected the resized box to overlap " + NEIGHBOUR_LABEL);
			}
			lookAt(context, box.min(), box.max());
			context.runOnClient(client -> client.gui.getChat().clearMessages(false));
			context.waitTicks(2);
			screenshotLastFrame(context, "mcvcs-vcs-resize-overlap");

			// Numpad 5 says why instead of confirming, and the command is refused by the server as well.
			context.getInput().pressKey(PlacePreviewKeys.CONFIRM);
			context.waitTicks(5);
			assertOverlay(context, ClientResize.REFUSED + "overlaps " + NEIGHBOUR_LABEL);
			assertOnlyMessage(run(context, "vcs confirmResize"), "Resizing " + LABEL + " to 2x3x5 would overlap " + NEIGHBOUR_LABEL);
			assertVersion(singleplayer, 1, new BuildBox(min, max));
			assertResizing(context, box);

			// One notch back and the box is clear; Numpad 5 commits it as v2 and ends the resize.
			box = scroll(context, box, 1.0, new BuildBox(min.offset(0, 0, -1), max.offset(0, 1, 1)));
			context.runOnClient(client -> RECEIVED.clear());
			context.getInput().pressKey(PlacePreviewKeys.CONFIRM);
			context.waitFor(client -> ClientResize.active() == null);
			context.waitTicks(5);
			assertOnlyMessage(context.computeOnClient(client -> List.copyOf(RECEIVED)), "Resized " + LABEL + " from 2x2x2 (8 blocks) to 2x3x4 (24 blocks) and saved it as v2");
			assertVersion(singleplayer, 2, box);
			assertSize(read(schematic(BUILD_NAME, 2)), new Vec3i(2, 3, 4));

			// A resize that is cancelled changes nothing.
			assertOnlyMessage(run(context, "vcs resize"), "Resizing " + LABEL + " (2x3x4)");
			assertResizing(context, box);
			scroll(context, box, -1.0, new BuildBox(box.min().offset(0, 0, -1), box.max()));
			assertOnlyMessage(run(context, "vcs cancelResize"), "Stopped resizing " + LABEL);
			context.waitFor(client -> ClientResize.active() == null);
			assertVersion(singleplayer, 2, box);
			if (Files.exists(schematic(BUILD_NAME, 3))) {
				throw new AssertionError("Expected no v3 after a cancelled resize");
			}
		}
	}

	/** Looks at the box, turns the wheel {@code amount} notches with the scroll key held, and waits for {@code expected}. */
	private static BuildBox scroll(ClientGameTestContext context, BuildBox box, double amount, BuildBox expected) {
		lookAt(context, box.min(), box.max());
		return scrollAsIs(context, amount, expected);
	}

	/** {@link #scroll} without turning to the box first. */
	private static BuildBox scrollAsIs(ClientGameTestContext context, double amount, BuildBox expected) {
		context.getInput().holdKey(PlacePreviewKeys.SCROLL);
		try {
			context.getInput().scroll(amount);
		} finally {
			context.getInput().releaseKey(PlacePreviewKeys.SCROLL);
		}
		assertResizing(context, expected);
		return expected;
	}

	/** The face the wheel drags is {@code expected}, null meaning none is picked. */
	private static void assertTarget(ClientGameTestContext context, ClientResize.Target expected) {
		ClientResize.Target actual = context.computeOnClient(ClientResize::target);
		if (!java.util.Objects.equals(actual, expected)) {
			throw new AssertionError("Expected the wheel to drag " + expected + " but it drags " + actual);
		}
	}

	private static void assertResizing(ClientGameTestContext context, BuildBox box) {
		try {
			context.waitFor(client -> {
				ClientResize.Resize resize = ClientResize.active();
				return resize != null && resize.box().equals(box);
			});
		} catch (RuntimeException | AssertionError e) {
			ClientResize.Resize resize = context.computeOnClient(client -> ClientResize.active());
			throw new AssertionError("Expected resizing to " + box + " but got " + (resize == null ? "none" : resize.box()), e);
		}
	}

	private static void assertVersion(TestSingleplayerContext singleplayer, int version, BuildBox box) {
		Optional<Build> build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, BUILD_NAME));
		if (build.isEmpty() || build.get().version() != version || !mainBox(build.get()).equals(box)) {
			throw new AssertionError("Expected '" + BUILD_NAME + "' v" + version + " with box " + box + " but got " + build.orElse(null));
		}
	}

	private static void assertOverlay(ClientGameTestContext context, String expected) {
		String message = context.computeOnClient(VcsTestSupport::overlayMessage);
		if (!expected.equals(message)) {
			throw new AssertionError("Expected the action bar to say '" + expected + "' but it says '" + message + "'");
		}
	}

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
}
