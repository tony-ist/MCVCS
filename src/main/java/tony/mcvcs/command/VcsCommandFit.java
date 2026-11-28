package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
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

/**
 * {@code /vcs fit}: fits the selected placement's box to the build inside it and saves the fitted box as the build's
 * next version. The box first grows until only air surrounds it, so whatever was built out past its edges is inside
 * it again, see {@link BoxExpansion}; then it shrinks to the smallest box holding every block in it that is not air,
 * so layers of nothing but air along its sides are dropped.
 * <p>
 * A box belongs to a version, not to a placement, so the fit is a new version with another extent: the placement
 * that was fitted holds it straight away, and every other placement keeps the box of whatever version it holds until
 * it checks this one out. Earlier versions stay the size they were; one smaller than the fitted box is laid inside it
 * where it was built, see {@link BoxSnapshot#ofSchematic}, and one that no longer fits it can only be checked out.
 */
public final class VcsCommandFit {
	static final VcsHelp HELP = new VcsHelp("fit", "/vcs fit",
		"fit the box to the build, then save",
		"Grows the selected build's box over anything you built past its edges, trims its empty sides, and saves the result as a new version. Anything touching the box is taken in, so keep builds surrounded by air. Refuses if the new box would overlap another copy or be over " + BoxExpansion.MAX_VOLUME + " blocks.");

	private VcsCommandFit() {
	}

	/**
	 * Fits the selected placement's box to the build inside it and saves the result as the build's next version. The
	 * commit is part of the fit: blocks the box just took in are not in any version yet, and the version the box held
	 * no longer has its size, so without it the placement would count as modified right away and
	 * {@code /vcs load} would refuse.
	 */
	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		BuildPlacement placement = selected.get();
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(VcsMessages.missingDimension(placement));
			return 0;
		}

		BuildBox box = placement.box();
		BoxExpansion expansion = BoxExpansion.of(box, level);
		if (!expansion.enclosed()) {
			source.sendFailure(Component.empty().append(VcsMessages.placement(placement)).append(" touches blocks outside its box, but taking them in would grow it past the limit of "
				+ BoxExpansion.MAX_VOLUME + " blocks; nothing was changed"));
			return 0;
		}
		// The grown box is enclosed by air, so trimming the air off its sides leaves one that is enclosed as well.
		BuildBox fitted = BoxExpansion.nonAirWithin(expansion.to(), level);
		if (fitted == null) {
			source.sendFailure(Component.empty().append(VcsMessages.placement(placement)).append(" is empty; there is nothing to fit"));
			return 0;
		}
		if (fitted.equals(box)) {
			source.sendSuccess(() -> Component.empty().append(VcsMessages.placement(placement)).append(" already fits; nothing changed"), false);
			return 0;
		}
		// Every block belongs to at most one placement, so a box that reaches into another one is refused.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), fitted, placement);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Fitting ").append(VcsMessages.placement(placement)).append(" to " + VcsMessages.size(fitted))
				.append(" would overlap ").append(VcsMessages.placement(overlapping.get())).append(VcsMessages.NO_OVERLAP));
			return 0;
		}

		Build build = placement.build().withNextVersion(fitted.relativeTo(placement.origin()));
		int version = build.version();
		build = build.withPlacement(placement.name(), placement.placement().withHead(version));

		try {
			BuildSaver.save(player, build, version, fitted, level);
			BuildRegistry.select(player, build, placement.name());

			String verb = fitted.contains(box) ? "Expanded" : box.contains(fitted) ? "Shrank" : "Fitted";
			source.sendSuccess(() -> Component.literal(verb + " ").append(VcsMessages.placement(placement)).append(" from " + VcsMessages.size(box) + " (" + box.volume() + " blocks) to "
				+ VcsMessages.size(fitted) + " (" + fitted.volume() + " blocks) and saved it as v" + version), false);
			return 1;
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to fit '{}' to v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save: " + e.getMessage()));
			return 0;
		}
	}
}
