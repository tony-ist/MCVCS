package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs select <buildname>}: makes an existing build the player's selected build, so its bounding box is shown
 * and later commands act on it.
 */
public final class VcsCommandSelect {
	private VcsCommandSelect() {
	}

	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world; create it with ").append(ChatButtons.command("/vcs create " + buildName)));
			return 0;
		}

		try {
			BuildRegistry.select(player, build.get());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to select build '{}' for {}", buildName, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save selection: " + e.getMessage()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Selected build ").append(VcsMessages.name(buildName)).append(" v" + build.get().version() + " (" + build.get().box().volume() + " blocks)"), false);
		return 1;
	}
}
