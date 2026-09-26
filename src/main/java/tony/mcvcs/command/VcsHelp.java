package tony.mcvcs.command;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import tony.mcvcs.network.ChatButtons;

/**
 * Help text for one {@code /vcs} subcommand, shown by {@code /vcs <name> -h} and, one line each, by {@code /vcs help};
 * every subcommand class holds its own as a {@code HELP} constant and {@link VcsCommandHelp} lists them. The chat
 * looks the way WorldEdit's {@code //help} does: a gray {@code ?} that opens the command's help, the command in gold,
 * which puts itself into the chat box when clicked, and the description after a colon.
 * <p>
 * In the summary, the details and the keys' descriptions a command is written between backticks, e.g.
 * {@code `/vcs diff off`}; it is shown in green without them and clicking it puts it into the chat box.
 *
 * @param name    the subcommand's literal, e.g. {@code create}
 * @param usage   how it is typed, with its arguments, e.g. {@code /vcs create <buildname>}
 * @param summary one line, without a full stop, for the {@code /vcs help} listing
 * @param details the whole story for {@code /vcs <name> -h}; may be several sentences
 * @param keys    the flags the subcommand takes, each shown on a line of its own under the details
 */
record VcsHelp(String name, String usage, String summary, String details, List<Key> keys) {
	/** Flag after any subcommand that shows its help instead of running it. */
	static final String FLAG = "-h";

	/** Marks the start and the end of a command in the text. */
	private static final char COMMAND_QUOTE = '`';

	/**
	 * One flag of a subcommand and what it does.
	 *
	 * @param key         the flag, e.g. {@code -f}
	 * @param description what it does, without a full stop
	 */
	record Key(String key, String description) {
	}

	VcsHelp(String name, String usage, String summary, String details, Key... keys) {
		this(name, usage, summary, details, List.of(keys));
	}

	/** The subcommand with its {@code /vcs} in front, e.g. {@code /vcs create}. */
	String command() {
		return "/vcs " + name;
	}

	/** The usage in gold; clicking it puts the command, ready for its arguments, into the chat box. */
	MutableComponent usageComponent() {
		return Component.literal(usage).withStyle(style -> style.withColor(ChatFormatting.GOLD)
			.withClickEvent(new ClickEvent.SuggestCommand(command() + " "))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to type " + command()))));
	}

	/** One line of the {@code /vcs help} listing: a gray {@code ?} that opens this help, the usage and the summary. */
	MutableComponent listingLine() {
		MutableComponent more = Component.literal("? ").withStyle(style -> style.withColor(ChatFormatting.GRAY)
			.withClickEvent(ChatButtons.run(VcsCommandHelp.HELP.command() + " " + name))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click for help on " + command()))));
		return more.append(usageComponent()).append(Component.literal(": ").withStyle(ChatFormatting.WHITE)).append(text(summary));
	}

	/**
	 * Sends a bordered box headed by the command, with its usage, the details and a line per key inside, as
	 * {@code /vcs <name> -h} does.
	 */
	void send(CommandSourceStack source) {
		source.sendSuccess(() -> VcsMessages.header("Help for " + command()), false);
		source.sendSuccess(() -> Component.literal("Usage: ").withStyle(ChatFormatting.GRAY).append(usageComponent()), false);
		source.sendSuccess(() -> text(details), false);
		for (Key key : keys) {
			source.sendSuccess(() -> Component.literal("  " + key.key()).withStyle(ChatFormatting.GOLD)
				.append(Component.literal(": ").withStyle(ChatFormatting.WHITE)).append(text(key.description())), false);
		}
	}

	/** {@code text} in white, with every command between backticks in green instead, see {@link #command(String)}. */
	static MutableComponent text(String text) {
		MutableComponent result = Component.empty().withStyle(ChatFormatting.WHITE);
		String[] parts = text.split(String.valueOf(COMMAND_QUOTE), -1);
		if (parts.length % 2 == 0) {
			throw new IllegalArgumentException("Unmatched " + COMMAND_QUOTE + " in help text: " + text);
		}
		for (int i = 0; i < parts.length; i++) {
			if (i % 2 == 0) {
				result.append(parts[i]);
			} else {
				result.append(command(parts[i]));
			}
		}
		return result;
	}

	/**
	 * {@code command} in green; clicking it puts it into the chat box, only up to its first {@code <placeholder>} if
	 * it has one, rather than running it, since an example such as {@code /vcs commit 2.0.0} is not meant to be run.
	 */
	private static MutableComponent command(String command) {
		int placeholder = command.indexOf('<');
		String typed = placeholder < 0 ? command : command.substring(0, placeholder);
		return Component.literal(command).withStyle(style -> style.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.SuggestCommand(typed))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to type " + typed.strip()))));
	}
}
