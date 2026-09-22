package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.PreviewSender;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

/**
 * {@code /vcs preview <version>}: sends that version's schematic to the player's client, which draws it in place of
 * the real blocks inside the selected placement's box. Nothing in the world changes. {@code /vcs preview off} shows
 * the real blocks again.
 * <p>
 * The version is drawn where the selected placement would hold it, so previewing at one placement says nothing about
 * the others. A version too big for the box it is previewed in is refused; check it out to see it at its own size.
 */
public final class VcsCommandPreview {
	static final VcsHelp HELP = new VcsHelp("preview", "/vcs preview <version | off>",
		"show a version in place of the real blocks, on your client only",
		"Draws that version of the build inside the selected placement's box instead of the real blocks. Nothing in the world changes, and only you see it; the mod has to be installed on your client. /vcs preview off shows the real blocks again.");

	private VcsCommandPreview() {
	}

	static int run(CommandSourceStack source, int version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot show previews"));
			return 0;
		}

		BuildPlacement placement = selected.get();
		Build build = placement.build();
		if (version > build.version()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" only has versions 1 to " + build.version()));
			return 0;
		}

		BuildBox box = placement.box();
		BuildBox covered = placement.boxOf(version);
		if (!box.contains(covered)) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" v" + version + " is " + VcsMessages.size(covered)
				+ ", which does not fit the " + VcsMessages.size(box) + " box of ").append(VcsMessages.placement(placement))
				.append("; run ").append(ChatButtons.command("/vcs checkout " + version)).append(" to see it here instead"));
			return 0;
		}

		try {
			Clipboard clipboard = BuildStorage.readSchematic(build.name(), version);
			PreviewSender.send(player, placement, version, clipboard);

			source.sendSuccess(() -> Component.literal("Previewing ").append(VcsMessages.placement(placement)).append(" at v" + version
				+ " (" + box.volume() + " blocks); run ").append(ChatButtons.command("/vcs preview off")).append(" to stop"), false);
			return 1;
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" v" + version + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to preview '{}' at v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}
	}

	/** {@code /vcs preview off}. */
	static int off(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot show previews"));
			return 0;
		}

		PreviewSender.clear(player);
		source.sendSuccess(() -> Component.literal("Preview off"), false);
		return 1;
	}
}
