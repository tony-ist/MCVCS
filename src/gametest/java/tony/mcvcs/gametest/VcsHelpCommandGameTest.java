package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * {@code /vcs <command> -h} shows the usage in gold, the details in white with every command in them in green, and
 * each of the command's keys on a line of its own, the key in gold. {@code /vcs help} shows commands in the summaries
 * in green as well.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsHelpCommandGameTest extends VcsGameTest {
	private static final TextColor GOLD = TextColor.fromLegacyFormat(ChatFormatting.GOLD);
	private static final TextColor GREEN = TextColor.fromLegacyFormat(ChatFormatting.GREEN);
	private static final TextColor WHITE = TextColor.fromLegacyFormat(ChatFormatting.WHITE);

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	/** A run of text in one colour, as the chat draws it. */
	private record Run(String text, TextColor color) {
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Header, usage, details and one line for -f.
			List<Component> place = help(context, "vcs place -h");
			if (place.size() != 4) {
				throw new AssertionError("Expected a header, the usage, the details and one key but got " + strings(place));
			}
			assertRun(place.get(1), "/vcs place <buildname> [version | tag | latest] [placementname] [-f]", GOLD);
			assertRun(place.get(2), "/vcs confirmPlace", GREEN);
			assertRun(place.get(2), "/vcs cancelPlace", GREEN);
			assertNoBackticks(place.get(2));
			assertRun(place.get(3), "  -f", GOLD);
			if (!place.get(3).getString().startsWith("  -f: overwrite")) {
				throw new AssertionError("Unexpected key line '" + place.get(3).getString() + "'");
			}
			openChat(context);
			screenshotLastFrame(context, "mcvcs-vcs-help-place");
			context.setScreen(() -> null);

			// Unplace's details name commands too, and its key is -k.
			List<Component> unplace = help(context, "vcs unplace -h");
			assertRun(unplace.get(2), "/vcs confirmUnplace", GREEN);
			assertRun(unplace.get(unplace.size() - 1), "  -k", GOLD);

			// A command without keys ends with its details.
			List<Component> tag = help(context, "vcs tag -h");
			if (tag.size() != 3) {
				throw new AssertionError("Expected a header, the usage and the details but got " + strings(tag));
			}
			assertRun(tag.get(2), "/vcs commit <tagname>", GREEN);

			// The listing: the usage stays gold, commands in the summaries turn green.
			List<Component> listing = help(context, "vcs help");
			Component confirmPlace = listing.stream().filter(line -> line.getString().startsWith("? /vcs confirmPlace")).findFirst()
				.orElseThrow(() -> new AssertionError("No /vcs confirmPlace line in " + strings(listing)));
			assertRun(confirmPlace, "/vcs confirmPlace [-f]", GOLD);
			assertRun(confirmPlace, "/vcs place", GREEN);
			assertRun(confirmPlace, "place the copy your last ", WHITE);
			for (Component line : listing) {
				assertNoBackticks(line);
			}
			openChat(context);
			screenshotLastFrame(context, "mcvcs-vcs-help");
			context.setScreen(() -> null);
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> help(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/** Opens the chat, which shows more lines than the HUD does, and waits for it to be drawn. */
	private static void openChat(ClientGameTestContext context) {
		context.setScreen(() -> new ChatScreen("", false));
		context.waitTicks(5);
	}

	/** That {@code line} has a run of exactly {@code text} in {@code color}. */
	private static void assertRun(Component line, String text, TextColor color) {
		List<Run> runs = runs(line);
		if (!runs.contains(new Run(text, color))) {
			throw new AssertionError("Expected '" + text + "' in " + color + " in " + runs);
		}
	}

	private static void assertNoBackticks(Component line) {
		if (line.getString().contains("`")) {
			throw new AssertionError("A backtick was left in '" + line.getString() + "'");
		}
	}

	/** The text of {@code component} split where its colour changes. */
	private static List<Run> runs(Component component) {
		List<Run> runs = new ArrayList<>();
		component.visit((Style style, String text) -> {
			if (!text.isEmpty()) {
				runs.add(new Run(text, style.getColor()));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return runs;
	}

	private static List<String> strings(List<Component> components) {
		return components.stream().map(Component::getString).toList();
	}
}
