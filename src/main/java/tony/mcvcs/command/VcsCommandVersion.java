package tony.mcvcs.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import tony.mcvcs.network.VersionCheck;

/** {@code /vcs version}: tells which version of the mod the server runs. */
public final class VcsCommandVersion {
	static final VcsHelp HELP = new VcsHelp("version", "/vcs version",
		"the version of MCVCS the server runs",
		"Shows the version of MCVCS installed on the server, which may differ from the one on your client.");

	private VcsCommandVersion() {
	}

	static int run(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("Server runs MCVCS " + VersionCheck.version()), false);
		return 1;
	}
}
