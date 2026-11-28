package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs save [tagname]}: saves what is inside the selected placement's box as the build's next version, tagged
 * with {@code tagname} if one is given, see {@link VcsCommandTag}. If anything other than air is touching the box, the
 * version is saved all the same but a yellow warning points the player at {@code /vcs fit}. A box that holds
 * exactly the version the placement is at has nothing to commit, and no version is saved.
 * <p>
 * Versions belong to the build, not to the placement they were committed from: every other placement of the build
 * can check the new version out, wherever it stands. The committing placement is the only one whose head moves, so
 * the others go on holding whatever version they held.
 */
public final class VcsCommandCommit {
	static final VcsHelp HELP = new VcsHelp("save", "/vcs save [label]",
		"save the selected build as a new version",
		"Saves the selected build as its next version. Its other copies can then load that version. Does nothing if nothing changed. Add a label to name the version, e.g. `/vcs save with-roof`.");

	private VcsCommandCommit() {
	}

	/** What the command says, in yellow, after saving a copy that has blocks touching its box. */
	public static MutableComponent notEnclosedWarning() {
		return Component.literal("Warning: blocks touching the build's box were not saved; run ")
			.append(ChatButtons.command("/vcs fit"))
			.append(" to grow the box over them")
			.withStyle(ChatFormatting.YELLOW);
	}

	/** @param tag what to tag the new version with, see {@link VcsCommandTag}, or {@code null} for no tag */
	static int run(CommandSourceStack source, @Nullable String tag) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		// Checked before anything is saved, so a tag that cannot be given leaves no untagged version behind.
		Optional<MutableComponent> refusal = tag == null ? Optional.empty() : VcsCommandTag.refusal(placement.build(), tag);
		if (refusal.isPresent()) {
			source.sendFailure(refusal.get().append("; nothing was saved"));
			return 0;
		}
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(VcsMessages.missingDimension(placement));
			return 0;
		}

		// The box does not change on a commit, so the new version covers exactly what the placement holds now.
		BuildBox box = placement.box();
		if (unchanged(placement, level, player)) {
			MutableComponent nothing = Component.literal("Nothing to save: ").append(VcsMessages.placement(placement))
				.append(" has no changes since " + placement.build().versionLabel(placement.head()));
			if (tag != null) {
				nothing.append("; run ").append(ChatButtons.command("/vcs label " + placement.head() + " " + tag)).append(" to label that version");
			}
			source.sendFailure(nothing);
			return 0;
		}
		BuildBox extent = placement.build().extent(placement.head());
		Build build =placement.build().withNextVersion(extent);
		int version = build.version();
		build = build.withPlacement(placement.name(), placement.placement().withHead(version));
		if (tag != null) {
			build = build.withTag(tag, version);
		}
		Build committed = build;

		// Blocks touching the box are probably part of the build and are about to be left out of the version.
		boolean enclosed = BoxExpansion.isEnclosed(box, level);

		try {
			BuildSaver.save(player, build, version, box, level);
			BuildRegistry.select(player, build, placement.name());

			source.sendSuccess(() -> Component.literal("Saved ").append(VcsMessages.placement(placement)).append(" as " + committed.versionLabel(version) + " (" + box.volume() + " blocks)"), false);
			if (!enclosed) {
				source.sendSuccess(VcsCommandCommit::notEnclosedWarning, false);
			}
			return 1;
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to commit '{}' as v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save: " + e.getMessage()));
			return 0;
		}
	}

	/**
	 * Whether every block inside {@code placement}'s box is the same as in the version it holds, so a commit would only
	 * save a copy of it. A version whose schematic cannot be read counts as changed: committing then is harmless, while
	 * refusing on the strength of a comparison that could not be made could leave the work unsaved.
	 */
	private static boolean unchanged(BuildPlacement placement, ServerLevel level, ServerPlayer player) {
		BuildBox box = placement.box();
		try {
			return BuildDiff.between(
				BoxSnapshot.ofSchematic(box, BuildStorage.readSchematic(placement.build().name(), placement.head()), box),
				BoxSnapshot.ofLevel(box, level)).size() == 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.warn("Failed to compare '{}' with v{} for {}, so it is committed without checking for changes",
				placement.label(), placement.head(), player.getGameProfile().name(), e);
			return false;
		}
	}
}
