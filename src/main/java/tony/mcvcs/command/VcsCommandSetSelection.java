package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.ChatButtons;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs setSelection}: gives the selected placement the bounding box of the player's WorldEdit selection as its
 * box and saves what is inside it as the build's next version, the way {@code /vcs fit} does with the box it finds.
 * <p>
 * The new box may be anywhere: bigger, smaller, or only partly over the old one. Blocks of the old box that the new
 * one leaves out stay standing in the world but are no longer part of the build, which the player is warned about.
 * Like a fit, the new box is a new version with another extent, so earlier versions keep their size, see
 * {@link BoxSnapshot#ofClipboard} and {@link BuildPlacement#viewBoxOf}.
 */
public final class VcsCommandSetSelection {
	static final VcsHelp HELP = new VcsHelp("setSelection", "/vcs setSelection",
		"make your WorldEdit selection the placement's box, then commit",
		"Gives the selected placement the bounding box of your WorldEdit selection as its box and saves what is inside it as the build's next version. Blocks of the old box outside the new one stay in the world but leave the build. Refuses if the new box would overlap another placement. `/vcs weselect` gives you the current box to start from.");

	private VcsCommandSetSelection() {
	}

	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		// WorldEdit keeps the selection of the world the player is in, which says nothing about another dimension.
		if (!player.level().dimension().equals(placement.dimension())) {
			source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier()
				+ " and you are in " + player.level().dimension().identifier() + "; run ").append(ChatButtons.command("/vcs tp " + placement.build().name() + " " + placement.name())).append(" first"));
			return 0;
		}
		ServerLevel level = player.level();

		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		BuildBox chosen;
		try {
			chosen = BuildBox.of(session.getSelection(actor.getWorld()));
		} catch (IncompleteRegionException e) {
			source.sendFailure(Component.literal("No WorldEdit selection to give ").append(VcsMessages.placement(placement))
				.append("; make one with the wand, or run ").append(ChatButtons.command("/vcs weselect")).append(" to start from its current box"));
			return 0;
		}

		BuildBox box = placement.box();
		if (chosen.equals(box)) {
			source.sendSuccess(() -> Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" already has your WorldEdit selection as its box; nothing to change"), false);
			return 0;
		}
		// Every block belongs to at most one placement, so a box that reaches into another one is refused.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), chosen, placement);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Setting the box of ").append(VcsMessages.placement(placement)).append(" to " + VcsMessages.size(chosen))
				.append(" would overlap ").append(VcsMessages.placement(overlapping.get())).append("; placements may not intersect"));
			return 0;
		}

		Build build = placement.build().withNextVersion(chosen.relativeTo(placement.origin()));
		int version = build.version();
		build = build.withPlacement(placement.name(), placement.placement().withHead(version));
		int leftOut = nonAirOutside(box, chosen, level);
		boolean enclosed = BoxExpansion.isEnclosed(chosen, level);

		try {
			Path file = BuildSaver.save(actor, session, build, version, chosen, level);
			BuildRegistry.select(player, build, placement.name());

			source.sendSuccess(() -> Component.literal("Set the box of ").append(VcsMessages.placement(placement)).append(" from " + VcsMessages.size(box) + " (" + box.volume() + " blocks) to "
				+ VcsMessages.size(chosen) + " (" + chosen.volume() + " blocks) and committed it as v" + version + " at " + BuildStorage.root().relativize(file)), false);
			if (leftOut > 0) {
				source.sendSuccess(() -> Component.literal("Warning: " + leftOut + (leftOut == 1 ? " block" : " blocks") + " of the old box " + (leftOut == 1 ? "is" : "are")
					+ " outside the new one; " + (leftOut == 1 ? "it stays" : "they stay") + " in the world but " + (leftOut == 1 ? "is" : "are") + " no longer part of the build")
					.withStyle(ChatFormatting.YELLOW), false);
			}
			if (!enclosed) {
				source.sendSuccess(VcsCommandCommit::notEnclosedWarning, false);
			}
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to set the box of '{}' to {} as v{} for {}", placement.label(), chosen, version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}

	/** How many blocks inside {@code from} but outside {@code to} are not air in {@code level}. */
	private static int nonAirOutside(BuildBox from, BuildBox to, ServerLevel level) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(from.min(), from.max())) {
			if (!to.contains(pos) && !level.getBlockState(pos).isAir()) {
				count++;
			}
		}
		return count;
	}
}
