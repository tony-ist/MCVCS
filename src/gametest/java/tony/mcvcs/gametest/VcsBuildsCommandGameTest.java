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

import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.command.VcsCommand;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.build.ClientBuild;
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
			if (listed.size() != 3) {
				throw new AssertionError("Expected a header and two builds but got " + strings(listed));
			}
			if (!listed.get(0).getString().equals("2 builds in this world:")) {
				throw new AssertionError("Unexpected header '" + listed.get(0).getString() + "'");
			}
			assertLine(listed.get(1), FIRST, "[" + VcsCommand.SELECT_BUTTON + "]");
			assertLine(listed.get(2), SECOND, "[" + VcsCommand.SELECTED_MARKER + "]");
			if (button(listed.get(2)).isPresent()) {
				throw new AssertionError("The selected build must not get a button but got " + button(listed.get(2)).get());
			}

			// Pressing the first build's button selects it.
			ClickEvent.Custom button = button(listed.get(1)).orElseThrow(() -> new AssertionError("Expected a button on '" + FIRST + "' in " + listed.get(1).getString()));
			ClickEvent expected = ChatButtons.run("/vcs select " + FIRST);
			if (!button.equals(expected)) {
				throw new AssertionError("Expected the button to be " + expected + " but got " + button);
			}
			press(context, button);
			waitForSelection(context, FIRST);

			// Listing again shows the marker moved.
			List<Component> relisted = listBuilds(context);
			if (relisted.size() != 3) {
				throw new AssertionError("Expected a header and two builds but got " + strings(relisted));
			}
			assertLine(relisted.get(1), FIRST, "[" + VcsCommand.SELECTED_MARKER + "]");
			assertLine(relisted.get(2), SECOND, "[" + VcsCommand.SELECT_BUTTON + "]");
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

	private static void assertLine(Component line, String name, String ending) {
		String text = line.getString();
		if (!text.contains(" " + name + " v1 ") || !text.endsWith(ending)) {
			throw new AssertionError("Expected a line for '" + name + "' v1 ending with " + ending + " but got '" + text + "'");
		}
	}

	private static List<String> strings(List<Component> components) {
		return components.stream().map(Component::getString).toList();
	}

	private static void waitForSelection(ClientGameTestContext context, String name) {
		context.waitFor(client -> {
			ClientBuild selected = ClientBuilds.selected();
			return selected != null && selected.name().equals(name);
		});
	}
}
