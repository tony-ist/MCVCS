package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;

/**
 * {@code /vcs deselect}: leaves the player with no selected placement, so no bounding box is shown and commands that
 * need a selection refuse until one is made again. Any preview or diff highlighting of it is turned off with it.
 */
public final class VcsCommandDeselect {
	static final VcsHelp HELP = new VcsHelp("deselect", "/vcs deselect",
		"unselect your selected placement",
		"Leaves you with no selected placement: its box, and any preview or diff highlighting of it, disappear, and the commands that need a selection refuse until you select one again.");

	private VcsCommandDeselect() {
	}

	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("Nothing selected in this world"));
			return 0;
		}

		String label = selected.get().label();
		try {
			BuildRegistry.deselect(player);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to deselect '{}' for {}", label, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save selection: " + e.getMessage()));
			return 0;
		}
		// A preview or diff shows a version of the placement that is no longer selected, so it goes with the selection.
		if (PreviewSender.canSend(player)) {
			PreviewSender.clear(player);
		}
		if (DiffSender.canSend(player)) {
			DiffSender.clear(player);
		}
		source.sendSuccess(() -> Component.literal("Deselected ").append(VcsMessages.name(label)), false);
		return 1;
	}
}
