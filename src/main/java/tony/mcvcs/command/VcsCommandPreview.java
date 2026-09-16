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
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.PreviewSender;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

/**
 * {@code /vcs preview <version>}: sends that version's schematic to the player's client, which draws it in place of
 * the real blocks inside the build's box. Nothing in the world changes. {@code /vcs preview off} shows the real
 * blocks again.
 */
public final class VcsCommandPreview {
	private VcsCommandPreview() {
	}

	static int run(CommandSourceStack source, int version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot show previews"));
			return 0;
		}

		Build latest = selected.get();
		if (version > latest.version()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(latest.name())).append(" only has versions 1 to " + latest.version()));
			return 0;
		}

		Build build = latest.atVersion(version);

		try {
			Clipboard clipboard = BuildStorage.read(build);
			PreviewSender.send(player, build, clipboard);

			source.sendSuccess(() -> Component.literal("Previewing build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " (" + build.box().volume() + " blocks); run ").append(ChatButtons.command("/vcs preview off")).append(" to stop"), false);
			return 1;
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to preview build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
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
