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
import tony.mcvcs.build.Schematic;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.PreviewSender;

/**
 * {@code /vcs preview <version | tag>}: sends that version's schematic to the player's client, which draws it in place of
 * the real blocks inside the selected placement's box. Nothing in the world changes. {@code /vcs preview off} shows
 * the real blocks again.
 * <p>
 * The version is drawn where the selected placement would hold it, so previewing at one placement says nothing about
 * the others. A version bigger than the box, such as one from before {@code /vcs fit} shrank it, is drawn over both
 * boxes together, see {@link BuildPlacement#viewBoxOf}: past the placement's box the real blocks are hidden too, even
 * those of another placement, so what shows is the version alone.
 */
public final class VcsCommandPreview {
	static final VcsHelp HELP = new VcsHelp("preview", "/vcs preview <version | label | off>",
		"see a version without changing anything",
		"Shows that version, given by number or label, in place of the selected build, only to you. Nothing in the world changes. Needs MCVCS on your client. `/vcs preview off` goes back to normal.");

	private static final String NO_CLIENT_MOD = "Your client doesn't have MCVCS, so it can't show previews";

	private VcsCommandPreview() {
	}

	/** @param typed the version to preview, by number or tag */
	static int run(CommandSourceStack source, VersionRef typed) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal(NO_CLIENT_MOD));
			return 0;
		}

		BuildPlacement placement = selected.get();
		Build build = placement.build();
		// The command always names a version, so the fallback is never used.
		Optional<Integer> resolved = typed.resolve(source, build, placement.head());
		if (resolved.isEmpty()) {
			return 0;
		}
		int version = resolved.get();

		BuildBox box = placement.viewBoxOf(version);

		try {
			Schematic schematic = BuildStorage.readSchematic(build.name(), version);
			PreviewSender.send(player, placement, version, schematic);

			source.sendSuccess(() -> Component.literal("Previewing ").append(VcsMessages.placement(placement)).append(" at " + build.versionLabel(version)
				+ " (" + box.volume() + " blocks); run ").append(ChatButtons.command("/vcs preview off")).append(" to stop"), false);
			return 1;
		} catch (NoSuchFileException e) {
			source.sendFailure(VcsMessages.missingVersion(build.name(), version));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to preview '{}' at v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load v" + version + ": " + e.getMessage()));
			return 0;
		}
	}

	/** {@code /vcs preview off}. */
	static int off(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal(NO_CLIENT_MOD));
			return 0;
		}

		PreviewSender.clear(player);
		source.sendSuccess(() -> Component.literal("Preview off"), false);
		return 1;
	}
}
