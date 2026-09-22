package tony.mcvcs.command;

import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs help}: tells how to start a build, then lists every subcommand with a one-line summary, see
 * {@link VcsHelp}. {@code /vcs help <command>} and {@code /vcs <command> -h} show the whole help of one subcommand.
 */
public final class VcsCommandHelp {
	static final VcsHelp HELP = new VcsHelp("help", "/vcs help [command]",
		"this listing, or everything about one command",
		"Without a command: how to start a build and one line per command. With one: the same as running that command with " + VcsHelp.FLAG + " after it, e.g. /vcs commit " + VcsHelp.FLAG + ".");

	/** Every subcommand's help, in the order {@code /vcs help} lists them. */
	static final List<VcsHelp> ALL = List.of(
		VcsCommandCreate.HELP,
		VcsCommandCommit.HELP,
		VcsCommandPlace.HELP,
		VcsCommandPlace.CONFIRM_HELP,
		VcsCommandPlace.CANCEL_HELP,
		VcsCommandUnplace.HELP,
		VcsCommandUnplace.CONFIRM_HELP,
		VcsCommandSelect.HELP,
		VcsCommandBuilds.HELP,
		VcsCommandDeselect.HELP,
		VcsCommandPreview.HELP,
		VcsCommandLoad.HELP,
		VcsCommandDiff.HELP,
		VcsCommandExpand.HELP,
		VcsCommandCheckout.HELP,
		VcsCommandDelete.HELP,
		VcsCommandDelete.CONFIRM_HELP,
		VcsCommandTp.HELP,
		VcsCommandWeselect.HELP,
		HELP);

	private VcsCommandHelp() {
	}

	/** The overview: a bordered {@code Help} box with how to get going, then the listing. */
	static int run(CommandSourceStack source) {
		source.sendSuccess(() -> VcsMessages.header("MCVCS Help"), false);
		source.sendSuccess(() -> Component.literal("MCVCS keeps versions of your builds. To start one, run ").append(ChatButtons.template("/vcs create <buildname>"))
			.append(", then punch a block of the build with your hand: the build grows over everything connected to that block and is saved as version 1. Keep building and run ")
			.append(ChatButtons.command("/vcs commit")).append(" to save the next version. Click ? to the left of command for details.").withStyle(ChatFormatting.WHITE), false);
		for (VcsHelp help : ALL) {
			source.sendSuccess(help::listingLine, false);
		}
		return ALL.size();
	}

	/** {@code /vcs help <command>}: the whole help of the subcommand called {@code name}. */
	static int run(CommandSourceStack source, String name) {
		Optional<VcsHelp> help = ALL.stream().filter(candidate -> candidate.name().equals(name)).findFirst();
		if (help.isEmpty()) {
			source.sendFailure(Component.literal("No such command: /vcs " + name + "; run ").append(ChatButtons.command("/vcs help")).append(" to list them"));
			return 0;
		}
		help.get().send(source);
		return 1;
	}
}
