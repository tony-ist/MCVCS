package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.BuildSync;

/**
 * {@code /vcs rename <buildname> <newname>}: gives a build a new name, moving its folder and schematics and keeping
 * every version, tag, placement in every world and selection of it. The new name follows the rules of
 * {@code /vcs create} and may not be taken, up to letter case, by another build; changing only the case is allowed.
 * A {@code /vcs place}, {@code /vcs resize} or {@code /vcs unplace} still waiting under the old name is refused when
 * confirmed, as for a deleted build.
 */
public final class VcsCommandRename {
	static final VcsHelp HELP = new VcsHelp("rename", "/vcs rename <buildname> <newname>",
		"give a build a new name",
		"Renames the build, keeping all its versions, tags and placements. Builds are shared by all worlds, so it is renamed in every world, and anyone who had one of its placements selected keeps that selection. The new name may not be taken by another build.");

	private VcsCommandRename() {
	}

	static int run(CommandSourceStack source, String buildName, String newName) {
		MinecraftServer server = source.getServer();
		Optional<Build> found = BuildRegistry.find(server, buildName);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)));
			return 0;
		}
		Build build = found.get();
		if (newName.equals(build.name())) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" is already called that"));
			return 0;
		}
		if (!VcsCommandCreate.isNameValid(source, newName)) {
			return 0;
		}
		// The file system may not tell names apart by case, so neither does the check; only the build itself may match.
		Optional<Build> taken = BuildRegistry.findIgnoringCase(server, newName).filter(other -> !other.name().equals(build.name()));
		if (taken.isPresent()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(taken.get().name())).append(" already exists; choose another name"));
			return 0;
		}

		try {
			BuildStorage.rename(build, newName);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to rename build '{}' to '{}' for {}", buildName, newName, source.getTextName(), e);
			source.sendFailure(Component.literal("Failed to rename build: " + e.getMessage()));
			// Part of the rename may have happened, so the clients must hear what is on disk now.
			BuildSync.broadcast(server);
			return 0;
		}
		MCVCS.LOGGER.info("{} renamed build '{}' to '{}'", source.getTextName(), buildName, newName);
		// Every client's list, labels and possibly its selection carry the old name.
		BuildSync.broadcast(server);

		source.sendSuccess(() -> Component.literal("Renamed build ").append(VcsMessages.name(buildName)).append(" to ").append(VcsMessages.name(newName)), false);
		return 1;
	}
}
