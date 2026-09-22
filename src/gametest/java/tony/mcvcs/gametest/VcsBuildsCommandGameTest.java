package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;

import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.command.VcsCommandBuilds;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.ClientPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs builds} lists every build in the world with a {@code [Select]} button after each one that is not
 * selected; pressing the button selects that build with no confirmation dialog in between. The selected build is
 * marked instead.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsBuildsCommandGameTest extends VcsGameTest {
	private static final String FIRST = "gametest-builds-first";
	private static final String SECOND = "gametest-builds-second";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(FIRST, SECOND);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Nothing to list yet.
			List<Component> empty = listBuilds(context);
			if (empty.size() != 1 || !empty.get(0).getString().startsWith("No builds in this world")) {
				throw new AssertionError("Expected only a 'No builds' message but got " + strings(empty));
			}

			// Two 3x2x2 stone boxes: the first in front of and to the right of the player, the second to the left.
			BlockPos firstMin = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos firstMax = firstMin.offset(2, 1, 1);
			BlockPos secondMin = playerPos(singleplayer).offset(-4, 0, 2);
			BlockPos secondMax = secondMin.offset(2, 1, 1);
			fillBox(singleplayer, firstMin, firstMax, Blocks.STONE.defaultBlockState(), firstMin, Blocks.STONE.defaultBlockState());
			fillBox(singleplayer, secondMin, secondMax, Blocks.STONE.defaultBlockState(), secondMin, Blocks.STONE.defaultBlockState());

			select(singleplayer, firstMin, firstMax);
			runCommand(context, "vcs create " + FIRST);
			read(schematic(FIRST, 1));
			select(singleplayer, secondMin, secondMax);
			runCommand(context, "vcs create " + SECOND);
			read(schematic(SECOND, 1));
			// Creating selects, so the second build is the selected one now.
			waitForSelection(context, SECOND);

			// Header plus one line per build, in name order, with a button on the first and a marker on the second.
			lookAt(context, firstMin, firstMax);
			List<Component> listed = listBuilds(context);
			screenshotLastFrame(context, "mcvcs-vcs-builds");
			if (listed.size() != 5) {
				throw new AssertionError("Expected a header, two builds and their placements but got " + strings(listed));
			}
			if (!listed.get(0).getString().equals("2 builds in this world:")) {
				throw new AssertionError("Unexpected header '" + listed.get(0).getString() + "'");
			}
			assertBuildLine(listed.get(1), FIRST);
			assertPlacementLine(listed.get(2), "[" + VcsCommandBuilds.SELECT_BUTTON + "]");
			assertBuildLine(listed.get(3), SECOND);
			assertPlacementLine(listed.get(4), "[" + VcsCommandBuilds.SELECTED_MARKER + "]");
			if (button(listed.get(4)).isPresent()) {
				throw new AssertionError("The selected placement must not get a button but got " + button(listed.get(4)).get());
			}

			// Pressing the first build's button selects it.
			ClickEvent.Custom button = button(listed.get(2)).orElseThrow(() -> new AssertionError("Expected a button on '" + FIRST + "' in " + listed.get(2).getString()));
			ClickEvent expected = ChatButtons.run("/vcs select " + FIRST + " " + Build.MAIN);
			if (!button.equals(expected)) {
				throw new AssertionError("Expected the button to be " + expected + " but got " + button);
			}
			press(context, button);
			waitForSelection(context, FIRST);

			// Listing again shows the marker moved.
			List<Component> relisted = listBuilds(context);
			if (relisted.size() != 5) {
				throw new AssertionError("Expected a header, two builds and their placements but got " + strings(relisted));
			}
			assertPlacementLine(relisted.get(2), "[" + VcsCommandBuilds.SELECTED_MARKER + "]");
			assertPlacementLine(relisted.get(4), "[" + VcsCommandBuilds.SELECT_BUTTON + "]");
		}
	}

	/** Runs {@code /vcs builds} and returns every game message it produced, in order. */
	private static List<Component> listBuilds(ClientGameTestContext context) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, "vcs builds");
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/**
	 * Does what the client does when {@code button} is clicked in chat: sends it to the server as is, with no
	 * confirmation, see {@code Screen.defaultHandleGameClickEvent}.
	 */
	private static void press(ClientGameTestContext context, ClickEvent.Custom button) {
		context.runOnClient(client -> client.player.connection.send(new ServerboundCustomClickActionPacket(button.id(), button.payload())));
		context.waitTicks(2);
	}

	/** The first {@link ClickEvent.Custom} in {@code component} or its siblings, if any. */
	private static Optional<ClickEvent.Custom> button(Component component) {
		if (component.getStyle().getClickEvent() instanceof ClickEvent.Custom custom) {
			return Optional.of(custom);
		}
		for (Component sibling : component.getSiblings()) {
			Optional<ClickEvent.Custom> button = button(sibling);
			if (button.isPresent()) {
				return button;
			}
		}
		return Optional.empty();
	}

	/** The heading of one build: its name and its latest version. */
	private static void assertBuildLine(Component line, String name) {
		if (!line.getString().equals("- " + name + " v1")) {
			throw new AssertionError("Expected the heading of '" + name + "' v1 but got '" + line.getString() + "'");
		}
	}

	/** One placement under a build: the {@code main} one at v1, ending in a button or the marker. */
	private static void assertPlacementLine(Component line, String ending) {
		String text = line.getString();
		if (!text.startsWith("    " + Build.MAIN + " v1 (") || !text.endsWith(ending)) {
			throw new AssertionError("Expected a line for the '" + Build.MAIN + "' placement at v1 ending with " + ending + " but got '" + text + "'");
		}
	}

	private static List<String> strings(List<Component> components) {
		return components.stream().map(Component::getString).toList();
	}

	private static void waitForSelection(ClientGameTestContext context, String name) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name);
		});
	}
}
