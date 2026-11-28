package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
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
import tony.mcvcs.build.Schematic;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;

/**
 * {@code /vcs load <version | tag | latest> [-f]}: puts a version of the build into the selected placement, exactly
 * where that placement holds it, without a single block update, see {@link BuildPlacer}.
 * <p>
 * Versions may differ in size, so the placement's box changes with the version it holds: it is the version's extent
 * laid at the placement's origin, which never moves. Both the old box and the new one are emptied before the version
 * goes down, since both are the placement's own ground; what the new box takes in beyond the old one is not, so
 * anything standing there refuses the checkout unless {@link VcsCommand#FORCE} is given, and another placement
 * reaching into it refuses the checkout outright, as placements may never overlap.
 * <p>
 * It also refuses while the box differs from the version it holds, since those changes would be lost: they have to
 * be committed first, unless {@code -f} is given, which overwrites them for good. The checked-out version becomes the one the
 * placement holds, so checking out another version after it is allowed, and a commit from there saves the box as the
 * build's next version as usual. Any preview or diff highlighting the player had up is stopped, since both showed the
 * box as it was before.
 */
public final class VcsCommandCheckout {
	static final VcsHelp HELP = new VcsHelp("load", "/vcs load <version | label | latest> [" + VcsCommand.FORCE + "]",
		"replace the selected build with a saved version",
		"Replaces the selected build with that version, given by number or label. The box changes to that version's size. Refuses if you have unsaved changes, or if blocks stand where a bigger version would go. Refuses if another copy is in the way.",
		new VcsHelp.Key(VcsCommand.FORCE, "delete unsaved changes and the blocks in the way"));

	private VcsCommandCheckout() {
	}

	/**
	 * @param version the version to check out, by number or tag, or {@link VersionRef#DEFAULT} for the build's latest one
	 * @param force   whether to check out over uncommitted changes and blocks in the way instead of refusing
	 */
	static int run(CommandSourceStack source, VersionRef version, boolean force) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		Build build = placement.build();
		Optional<Integer> resolved = version.resolve(source, build, build.version());
		if (resolved.isEmpty()) {
			return 0;
		}
		int checkedOut = resolved.get();
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(VcsMessages.missingDimension(placement));
			return 0;
		}

		BuildBox from = placement.box();
		BuildBox to = placement.boxOf(checkedOut);
		// The new box may reach past the old one; those blocks belong to nobody yet, so nothing else may be there.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), to, placement);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Loading v" + checkedOut + " would grow ").append(VcsMessages.placement(placement))
				.append(" to " + VcsMessages.size(to) + ", overlapping ").append(VcsMessages.placement(overlapping.get()))
				.append(VcsMessages.NO_OVERLAP + ", so move one of them first"));
			return 0;
		}

		Schematic schematic;
		// How many blocks are overwritten by the checkout: uncommitted work inside the old box, and anything standing
		// where the new box reaches past it. Forcing overwrites both, and the message says so.
		int uncommitted;
		int inTheWay;
		try {
			// Whatever was built since the box last held a version is about to be wiped, so it has to be in a version first.
			BuildDiff changes = BuildDiff.between(
				BoxSnapshot.ofSchematic(from, BuildStorage.readSchematic(build.name(), placement.head()), from),
				BoxSnapshot.ofLevel(from, level));
			uncommitted = changes.size();
			inTheWay = nonAirOutside(to, from, level);
			if (uncommitted > 0 && !force) {
				source.sendFailure(Component.empty().append(VcsMessages.placement(placement)).append(" has " + VcsMessages.unsavedChanges(uncommitted) + " since v" + placement.head() + "; run ")
					.append(ChatButtons.command("/vcs save")).append(" first, ").append(ChatButtons.command("/vcs changes")).append(" to see them, or add " + VcsCommand.FORCE + " to delete them"));
				return 0;
			}
			if (inTheWay > 0 && !force) {
				source.sendFailure(Component.literal("Loading v" + checkedOut + " would grow ").append(VcsMessages.placement(placement))
					.append(" from " + VcsMessages.size(from) + " to " + VcsMessages.size(to) + ", overwriting " + inTheWay
						+ (inTheWay == 1 ? " block" : " blocks") + " in the way; clear them or add " + VcsCommand.FORCE + " to overwrite them"));
				return 0;
			}
			schematic = BuildStorage.readSchematic(build.name(), checkedOut);
		} catch (NoSuchFileException e) {
			source.sendFailure(VcsMessages.missingVersion(build.name(), checkedOut));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to check out '{}' v{} for {}", placement.label(), checkedOut, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load: " + e.getMessage()));
			return 0;
		}

		// Both boxes are the placement's own ground, so both are emptied; the version then goes back where this
		// placement holds it, which is not where the schematic was copied from unless it was this placement.
		List<BuildBox> clear = new ArrayList<>(List.of(to));
		if (!from.equals(to)) {
			clear.add(from);
		}
		try {
			BuildPlacer.place(level, clear, schematic, to);
		} catch (IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to check out '{}' v{} for {}", placement.label(), checkedOut, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} checked out '{}' v{} into {} in {}, overwriting {} uncommitted and {} foreign blocks",
			player.getGameProfile().name(), placement.label(), checkedOut, to.min().toShortString(), level.dimension().identifier(), uncommitted, inTheWay);
		// A preview shown before the checkout would hide the version that was just placed, and a diff highlighted before
		// it compared blocks that are gone now.
		if (PreviewSender.canSend(player)) {
			PreviewSender.clear(player);
		}
		if (DiffSender.canSend(player)) {
			DiffSender.clear(player);
		}

		// The placement holds this version now; the next checkout, and /vcs changes without a version, measure against it.
		try {
			BuildStorage.update(build.withPlacement(placement.name(), placement.placement().withHead(checkedOut)));
			// The box has changed size with the version, so every client's picture of it has to change too.
			BuildSync.broadcast(source.getServer());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to record v{} as checked out for '{}' for {}", checkedOut, placement.label(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Loaded ").append(VcsMessages.placement(placement)).append(" v" + checkedOut + " but failed to remember it: " + e.getMessage()));
			return 0;
		}

		source.sendSuccess(() -> {
			MutableComponent message = Component.literal("Loaded ").append(VcsMessages.placement(placement))
				.append(" " + build.versionLabel(checkedOut) + " (" + VcsMessages.size(to) + ", " + to.volume() + " blocks)");
			if (uncommitted > 0 || inTheWay > 0) {
				// Only a forced checkout gets here; those blocks are gone for good.
				message.append(", overwriting " + (uncommitted + inTheWay) + " " + (uncommitted + inTheWay == 1 ? "block" : "blocks"));
			}
			if (checkedOut == build.version()) {
				return message;
			}
			return message.append("; the latest is v" + build.version() + ", run ")
				.append(ChatButtons.command("/vcs load latest")).append(" to go back to it");
		}, false);
		return 1;
	}

	/**
	 * How many blocks inside {@code to} but outside {@code from} are not air: what a checkout that grows the
	 * placement's box would overwrite, none of which belongs to the build.
	 */
	private static int nonAirOutside(BuildBox to, BuildBox from, ServerLevel level) {
		if (from.contains(to)) {
			return 0;
		}
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(to.min(), to.max())) {
			if (!from.contains(pos) && !level.getBlockState(pos).isAir()) {
				count++;
			}
		}
		return count;
	}
}
