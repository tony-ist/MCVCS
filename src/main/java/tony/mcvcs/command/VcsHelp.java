package tony.mcvcs.command;

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
 *
 * @param name    the subcommand's literal, e.g. {@code create}
 * @param usage   how it is typed, with its arguments, e.g. {@code /vcs create <buildname>}
 * @param summary one line, without a full stop, for the {@code /vcs help} listing
 * @param details the whole story for {@code /vcs <name> -h}; may be several sentences
 */
record VcsHelp(String name, String usage, String summary, String details) {
	/** Flag after any subcommand that shows its help instead of running it. */
	static final String FLAG = "-h";

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
		return more.append(usageComponent()).append(Component.literal(": " + summary).withStyle(ChatFormatting.WHITE));
	}

	/** Sends a bordered box headed by the command, with its usage and the details inside, as {@code /vcs <name> -h} does. */
	void send(CommandSourceStack source) {
		source.sendSuccess(() -> VcsMessages.header("Help for " + command()), false);
		source.sendSuccess(() -> Component.literal("Usage: ").withStyle(ChatFormatting.GRAY).append(usageComponent()), false);
		source.sendSuccess(() -> Component.literal(details).withStyle(ChatFormatting.WHITE), false);
	}
}
