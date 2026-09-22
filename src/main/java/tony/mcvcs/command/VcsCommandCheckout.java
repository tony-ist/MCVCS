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
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

/**
 * {@code /vcs checkout <version | latest> [-f]}: puts a version of the build into the selected placement, exactly
 * where that placement holds it, without a single block update, as if {@code //perf off} were on.
 * <p>
 * Versions may differ in size, so the placement's box changes with the version it holds: it is the version's extent
 * laid at the placement's origin, which never moves. Both the old box and the new one are emptied before the version
 * goes down, since both are the placement's own ground; what the new box takes in beyond the old one is not, so
 * anything standing there refuses the checkout unless {@link VcsCommand#FORCE} is given, and another placement
 * reaching into it refuses the checkout outright, as placements may never overlap.
 * <p>
 * It also refuses while the box differs from the version it holds, since those changes would be lost: they have to
 * be committed first, unless {@code -f} is given, which overwrites them for good. The checkout is not put in the
 * player's WorldEdit history, so {@code //undo} never reverts it. The checked-out version becomes the one the
 * placement holds, so checking out another version after it is allowed, and a commit from there saves the box as the
 * build's next version as usual. Any preview or diff highlighting the player had up is stopped, since both showed the
 * box as it was before.
 */
public final class VcsCommandCheckout {
	static final VcsHelp HELP = new VcsHelp("checkout", "/vcs checkout <version | latest> [" + VcsCommand.FORCE + "]",
		"put a version back into the selected placement",
		"Empties the selected placement's box and puts the provided version into it, without block updates. The box becomes that version's size around the same origin. Refuses if the box has uncommitted changes, or if anything stands where a bigger version would reach: commit first, or add " + VcsCommand.FORCE + " to overwrite both. A placement in the way is always refused. /vcs diff starts to compare versions against this checked out version.");

	private VcsCommandCheckout() {
	}

	/**
	 * @param version the version to check out, or {@link VcsCommand#LATEST} for the build's latest one
	 * @param force   whether to check out over uncommitted changes and blocks in the way instead of refusing
	 */
	static int run(CommandSourceStack source, int version, boolean force) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		Build build = placement.build();
		if (version > build.version()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" only has versions 1 to " + build.version()));
			return 0;
		}
		int checkedOut = version == VcsCommand.LATEST ? build.version() : version;
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BuildBox from = placement.box();
		BuildBox to = placement.boxOf(checkedOut);
		// The new box may reach past the old one; those blocks belong to nobody yet, so nothing else may be there.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), to, placement);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Checking out v" + checkedOut + " would grow ").append(VcsMessages.placement(placement))
				.append(" to " + VcsMessages.size(to) + ", overlapping ").append(VcsMessages.placement(overlapping.get()))
				.append("; placements may not intersect, so move one of them out of the way first"));
			return 0;
		}

		Clipboard clipboard;
		// How many blocks are overwritten by the checkout: uncommitted work inside the old box, and anything standing
		// where the new box reaches past it. Forcing overwrites both, and the message says so.
		int uncommitted;
		int inTheWay;
		try {
			// Whatever was built since the box last held a version is about to be wiped, so it has to be in a version first.
			BuildDiff changes = BuildDiff.between(
				BoxSnapshot.ofClipboard(from, BuildStorage.readSchematic(build.name(), placement.head()), from),
				BoxSnapshot.ofLevel(from, level));
			uncommitted = changes.size();
			inTheWay = nonAirOutside(to, from, level);
			if (uncommitted > 0 && !force) {
				source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is modified: " + uncommitted
					+ (uncommitted == 1 ? " block differs" : " blocks differ") + " from v" + placement.head() + "; run ").append(ChatButtons.command("/vcs commit"))
					.append(" before checking out, ").append(ChatButtons.command("/vcs diff")).append(" to see the changes, or add " + VcsCommand.FORCE + " to discard them"));
				return 0;
			}
			if (inTheWay > 0 && !force) {
				source.sendFailure(Component.literal("Checking out v" + checkedOut + " would grow ").append(VcsMessages.placement(placement))
					.append(" from " + VcsMessages.size(from) + " to " + VcsMessages.size(to) + ", overwriting " + inTheWay
						+ (inTheWay == 1 ? " block" : " blocks") + " standing in the way; clear them or add " + VcsCommand.FORCE + " to overwrite them"));
				return 0;
			}
			clipboard = BuildStorage.readSchematic(build.name(), checkedOut);
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to check out '{}' v{} for {}", placement.label(), checkedOut, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}

		// Both boxes are the placement's own ground, so both are emptied; the version then goes back where this
		// placement holds it, which is not where the schematic was copied from unless it was this placement.
		List<BuildBox> clear = new ArrayList<>(List.of(to));
		if (!from.equals(to)) {
			clear.add(from);
		}
		try {
			BuildPlacer.place(player, level, clear, clipboard, to);
		} catch (WorldEditException e) {
			MCVCS.LOGGER.error("Failed to check out '{}' v{} for {}", placement.label(), checkedOut, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to place schematic: " + e.getMessage()));
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

		// The placement holds this version now; the next checkout, and /vcs diff without a version, measure against it.
		try {
			BuildStorage.update(build.withPlacement(placement.name(), placement.placement().withHead(checkedOut)));
			// The box has changed size with the version, so every client's picture of it has to change too.
			BuildSync.broadcast(source.getServer());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to record v{} as checked out for '{}' for {}", checkedOut, placement.label(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Checked out ").append(VcsMessages.placement(placement)).append(" v" + checkedOut + " but failed to record it: " + e.getMessage()));
			return 0;
		}

		source.sendSuccess(() -> {
			MutableComponent message = Component.literal("Checked out ").append(VcsMessages.placement(placement))
				.append(" v" + checkedOut + " (" + VcsMessages.size(to) + ", " + to.volume() + " blocks) without block updates");
			if (uncommitted > 0 || inTheWay > 0) {
				// Only a forced checkout gets here; those blocks are gone, and not into WorldEdit's history either.
				message.append(", overwriting " + (uncommitted + inTheWay) + " " + (uncommitted + inTheWay == 1 ? "block" : "blocks"));
			}
			if (checkedOut == build.version()) {
				return message;
			}
			return message.append("; it now holds v" + checkedOut + " rather than the latest v" + build.version() + ", run ")
				.append(ChatButtons.command("/vcs checkout latest")).append(" to go back to it");
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
