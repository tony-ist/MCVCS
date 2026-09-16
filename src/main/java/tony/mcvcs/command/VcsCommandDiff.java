package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.diff.ChangeKind;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;

/**
 * {@code /vcs diff [version]}: compares the blocks currently inside the build's box with that version, or the one the
 * box holds if no version is given (the latest, unless an earlier one was checked out, see {@link Build#head}), see
 * {@link BuildDiff}, reports how many were added, removed or changed since, and has the player's client highlight
 * them in place. {@code /vcs diff off} stops the highlighting.
 */
public final class VcsCommandDiff {
	static final VcsHelp HELP = new VcsHelp("diff", "/vcs diff [version | off]",
		"highlight what changed in the build since a version",
		"Compares the provided version (or latest version by default) with the current state of the build. With the mod on your client blocks are highlighted in place: green for added, red for removed, yellow for changed. /vcs diff off removes the highlights.");

	private VcsCommandDiff() {
	}

	/**
	 * Compares the blocks now inside the selected build's box with one of its versions. The summary goes to chat
	 * whether or not the player's client has this mod; the highlighting needs it.
	 *
	 * @param version the version to compare against, or {@link VcsCommand#LATEST} for the one the selected build's box
	 *                holds, see {@link Build#head}: the latest one unless an earlier one was checked out
	 */
	static int run(CommandSourceStack source, int version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}

		Build latest = selected.get();
		if (version > latest.version()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(latest.name())).append(" only has versions 1 to " + latest.version()));
			return 0;
		}
		Build build = version == VcsCommand.LATEST ? latest.atHead() : latest.atVersion(version);
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BuildDiff diff;
		try {
			// The version is the old side and the world the new one, so "added" reads as "built since that version".
			diff = BuildDiff.between(BoxSnapshot.ofClipboard(build.box(), BuildStorage.read(build)), BoxSnapshot.ofLevel(build.box(), level));
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to diff build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}

		boolean highlight = DiffSender.canSend(player);
		if (diff.isEmpty()) {
			// Nothing to highlight, and a stale highlight of an earlier diff would be misleading next to this message.
			if (highlight) {
				DiffSender.clear(player);
			}
			source.sendSuccess(() -> Component.literal("Build ").append(VcsMessages.name(build.name())).append(" matches v" + build.version() + "; nothing to highlight"), false);
			return 0;
		}

		if (highlight) {
			DiffSender.send(player, build, diff);
		}
		source.sendSuccess(() -> {
			MutableComponent summary = diffSummary(build, diff);
			return highlight
				? summary.append("; run ").append(ChatButtons.command("/vcs diff off")).append(" to stop highlighting")
				: summary.append("; install MCVCS on your client to see them highlighted");
		}, false);
		return diff.size();
	}

	/** E.g. {@code 5 blocks in build x differ from v2 (2 added, 1 removed, 2 changed)}. */
	private static MutableComponent diffSummary(Build build, BuildDiff diff) {
		Map<ChangeKind, Integer> counts = diff.counts();
		return Component.literal(diff.size() + (diff.size() == 1 ? " block" : " blocks") + " in build ").append(VcsMessages.name(build.name())).append(" "
			+ (diff.size() == 1 ? "differs" : "differ") + " from v" + build.version()
			+ " (" + counts.get(ChangeKind.ADDED) + " added, " + counts.get(ChangeKind.REMOVED) + " removed, " + counts.get(ChangeKind.CHANGED) + " changed)");
	}

	/** {@code /vcs diff off}. */
	static int off(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!DiffSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot highlight diffs"));
			return 0;
		}

		DiffSender.clear(player);
		source.sendSuccess(() -> Component.literal("Diff off"), false);
		return 1;
	}
}
