package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;
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
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs commit [tagname]}: saves what is inside the selected placement's box as the build's next version, tagged
 * with {@code tagname} if one is given, see {@link VcsCommandTag}. The box is the
 * one the placement holds, not the player's WorldEdit selection. If anything other than air is touching the box, the
 * version is saved all the same but a yellow warning points the player at {@code /vcs expand}. A box that holds
 * exactly the version the placement is at has nothing to commit, and no version is saved.
 * <p>
 * Versions belong to the build, not to the placement they were committed from: every other placement of the build
 * can check the new version out, wherever it stands. The committing placement is the only one whose head moves, so
 * the others go on holding whatever version they held.
 */
public final class VcsCommandCommit {
	static final VcsHelp HELP = new VcsHelp("commit", "/vcs commit [tagname]",
		"save the selected placement as the build's next version",
		"Saves what is inside the selected placement's box as the build's next version. WorldEdit selection does not matter, only the placement's box is concerned. Every other placement of the build can then check that version out. Refuses if nothing in the box has changed since the version the placement holds. With a tag name, e.g. `/vcs commit 2.0.0`, the new version is tagged.");

	private VcsCommandCommit() {
	}

	/** What the command says, in yellow, after committing a placement that has blocks touching its box. */
	public static MutableComponent notEnclosedWarning() {
		return Component.literal("Warning: the build is not enclosed by air, so blocks touching its box were left out; run ")
			.append(ChatButtons.command("/vcs expand"))
			.append(" to expand the build area")
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
			source.sendFailure(refusal.get().append("; nothing was committed"));
			return 0;
		}
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		// The box does not change on a commit, so the new version covers exactly what the placement holds now.
		BuildBox box = placement.box();
		if (unchanged(placement, level, player)) {
			MutableComponent nothing = Component.literal("Nothing to commit: ").append(VcsMessages.placement(placement))
				.append(" is the same as " + placement.build().versionLabel(placement.head()));
			if (tag != null) {
				nothing.append("; run ").append(ChatButtons.command("/vcs tag " + placement.head() + " " + tag)).append(" to tag that version");
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

		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		// Blocks touching the box are probably part of the build and are about to be left out of the version.
		boolean enclosed = BoxExpansion.isEnclosed(box, level);

		try {
			Path file = BuildSaver.save(actor, session, build, version, box, level);
			BuildRegistry.select(player, build, placement.name());

			source.sendSuccess(() -> Component.literal("Committed ").append(VcsMessages.placement(placement)).append(" as build ")
				.append(VcsMessages.name(placement.build().name())).append(" " + committed.versionLabel(version) + " (" + box.volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
			if (!enclosed) {
				source.sendSuccess(VcsCommandCommit::notEnclosedWarning, false);
			}
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to commit '{}' as v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
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
				BoxSnapshot.ofClipboard(box, BuildStorage.readSchematic(placement.build().name(), placement.head()), box),
				BoxSnapshot.ofLevel(box, level)).size() == 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.warn("Failed to compare '{}' with v{} for {}, so it is committed without checking for changes",
				placement.label(), placement.head(), player.getGameProfile().name(), e);
			return false;
		}
	}
}
