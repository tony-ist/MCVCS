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
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.Placement;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.diff.ChangeKind;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;

/**
 * {@code /vcs diff [version | tag]}: compares the blocks currently inside the selected placement's box with that version,
 * or the one the placement holds if no version is given, see {@link Placement#head}, reports how many were added,
 * removed or changed since, and has the player's client highlight them in place. {@code /vcs diff off} stops the
 * highlighting.
 * <p>
 * A version smaller than the box is laid inside it where the placement holds it and the rest of the box counts as
 * air, so a version from before an expand shows the blocks that expand took in as added.
 */
public final class VcsCommandDiff {
	static final VcsHelp HELP = new VcsHelp("diff", "/vcs diff [version | tag | off]",
		"highlight what changed in the placement since a version",
		"Compares the provided version, given by number or by tag (or the one the selected placement holds by default), with what is in its box now. With the mod on your client blocks are highlighted in place: green for added, red for removed, yellow for changed. /vcs diff off removes the highlights.");

	private VcsCommandDiff() {
	}

	/**
	 * Compares the blocks now inside the selected placement's box with one of the build's versions. The summary goes
	 * to chat whether or not the player's client has this mod; the highlighting needs it.
	 *
	 * @param version the version to compare against, by number or tag, or {@link VersionRef#DEFAULT} for the one the
	 *                placement holds
	 */
	static int run(CommandSourceStack source, VersionRef version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		Build build = placement.build();
		Optional<Integer> resolved = version.resolve(source, build, placement.head());
		if (resolved.isEmpty()) {
			return 0;
		}
		int against = resolved.get();
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BuildBox box = placement.box();
		BuildBox covered = placement.boxOf(against);
		if (!box.contains(covered)) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" v" + against + " is " + VcsMessages.size(covered)
				+ ", which does not fit the " + VcsMessages.size(box) + " box of ").append(VcsMessages.placement(placement))
				.append("; run ").append(ChatButtons.command("/vcs checkout " + against)).append(" to see it here instead"));
			return 0;
		}

		BuildDiff diff;
		try {
			// The version is the old side and the world the new one, so "added" reads as "built since that version".
			diff = BuildDiff.between(BoxSnapshot.ofClipboard(box, BuildStorage.readSchematic(build.name(), against), covered), BoxSnapshot.ofLevel(box, level));
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" v" + against + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to diff '{}' against v{} for {}", placement.label(), against, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}

		boolean highlight = DiffSender.canSend(player);
		if (diff.isEmpty()) {
			// Nothing to highlight, and a stale highlight of an earlier diff would be misleading next to this message.
			if (highlight) {
				DiffSender.clear(player);
			}
			source.sendSuccess(() -> Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" matches v" + against + "; nothing to highlight"), false);
			return 0;
		}

		if (highlight) {
			DiffSender.send(player, placement, against, diff);
		}
		source.sendSuccess(() -> {
			MutableComponent summary = diffSummary(placement, against, diff);
			return highlight
				? summary.append("; run ").append(ChatButtons.command("/vcs diff off")).append(" to stop highlighting")
				: summary.append("; install MCVCS on your client to see them highlighted");
		}, false);
		return diff.size();
	}

	/** E.g. {@code 5 blocks in tower/main differ from v2 (2 added, 1 removed, 2 changed)}. */
	private static MutableComponent diffSummary(BuildPlacement placement, int version, BuildDiff diff) {
		Map<ChangeKind, Integer> counts = diff.counts();
		return Component.literal(diff.size() + (diff.size() == 1 ? " block in " : " blocks in ")).append(VcsMessages.placement(placement)).append(" "
			+ (diff.size() == 1 ? "differs" : "differ") + " from v" + version
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
