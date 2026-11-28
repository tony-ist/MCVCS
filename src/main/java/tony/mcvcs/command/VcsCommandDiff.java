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
 * {@code /vcs changes [version | tag]}: compares the blocks currently inside the selected placement's box with that version,
 * or the one the placement holds if no version is given, see {@link Placement#head}, reports how many were added,
 * removed or changed since, and has the player's client highlight them in place. {@code /vcs changes off} stops the
 * highlighting.
 * <p>
 * A version smaller than the box is laid inside it where the placement holds it and the rest of the box counts as
 * air, so a version from before {@code /vcs fit} grew the box shows the blocks it took in as added. A version bigger
 * than the box, such as one from before {@code /vcs fit} shrank it, is compared over both boxes together, see
 * {@link BuildPlacement#viewBoxOf}, so its blocks outside the box show as removed, or as changed if something else
 * stands there now, whether or not that belongs to another placement.
 */
public final class VcsCommandDiff {
	static final VcsHelp HELP = new VcsHelp("changes", "/vcs changes [version | label | off]",
		"highlight what changed in the selected build",
		"Highlights the blocks that differ from that version, given by number or label, or from the version you last saved or loaded: green for added, red for removed, yellow for changed. Highlights need MCVCS on your client. `/vcs changes off` hides them.");

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
			source.sendFailure(VcsMessages.missingDimension(placement));
			return 0;
		}

		BuildBox box = placement.viewBoxOf(against);
		BuildBox covered = placement.boxOf(against);

		BuildDiff diff;
		try {
			// The version is the old side and the world the new one, so "added" reads as "built since that version".
			diff = BuildDiff.between(BoxSnapshot.ofSchematic(box, BuildStorage.readSchematic(build.name(), against), covered), BoxSnapshot.ofLevel(box, level));
		} catch (NoSuchFileException e) {
			source.sendFailure(VcsMessages.missingVersion(build.name(), against));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to diff '{}' against v{} for {}", placement.label(), against, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load v" + against + ": " + e.getMessage()));
			return 0;
		}

		boolean highlight = DiffSender.canSend(player);
		if (diff.isEmpty()) {
			// Nothing to highlight, and a stale highlight of an earlier diff would be misleading next to this message.
			if (highlight) {
				DiffSender.clear(player);
			}
			source.sendSuccess(() -> Component.empty().append(VcsMessages.placement(placement)).append(" has no changes since v" + against), false);
			return 0;
		}

		if (highlight) {
			DiffSender.send(player, placement, against, diff);
		}
		source.sendSuccess(() -> {
			MutableComponent summary = diffSummary(placement, against, diff);
			return highlight
				? summary.append("; run ").append(ChatButtons.command("/vcs changes off")).append(" to hide them")
				: summary.append("; install MCVCS on your client to see them highlighted");
		}, false);
		return diff.size();
	}

	/** E.g. {@code tower/main has 5 changed blocks since v2 (2 added, 1 removed, 2 changed)}. */
	private static MutableComponent diffSummary(BuildPlacement placement, int version, BuildDiff diff) {
		Map<ChangeKind, Integer> counts = diff.counts();
		return Component.empty().append(VcsMessages.placement(placement)).append(" has " + diff.size() + (diff.size() == 1 ? " changed block" : " changed blocks")
			+ " since v" + version
			+ " (" + counts.get(ChangeKind.ADDED) + " added, " + counts.get(ChangeKind.REMOVED) + " removed, " + counts.get(ChangeKind.CHANGED) + " changed)");
	}

	/** {@code /vcs changes off}. */
	static int off(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!DiffSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client doesn't have MCVCS, so it can't highlight changes"));
			return 0;
		}

		DiffSender.clear(player);
		source.sendSuccess(() -> Component.literal("Changes hidden"), false);
		return 1;
	}
}
