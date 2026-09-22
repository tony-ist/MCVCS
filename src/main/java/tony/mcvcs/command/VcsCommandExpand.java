package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;
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
import tony.mcvcs.build.BuildStorage;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs expand}: grows the selected placement's box until only air surrounds it, so whatever was built out past
 * its edges is inside it again, and saves the grown box as the build's next version.
 * <p>
 * A box belongs to a version, not to a placement, so the growth is a new version with a bigger extent: the placement
 * that expanded holds it straight away, and every other placement keeps the box of whatever version it holds until it
 * checks this one out. Earlier versions stay the size they were and are laid inside the grown box where they were
 * built, see {@link BoxSnapshot#ofClipboard}.
 */
public final class VcsCommandExpand {
	static final VcsHelp HELP = new VcsHelp("expand", "/vcs expand",
		"grow the box over blocks built past its edges, then commit",
		"Grows the selected placement's box until only air surrounds it, so whatever you built out past its edges is inside it again, and saves the grown box as the build's next version. Anything touching the box pulls it out to cover that block, which is why builds should hover in the air. Refuses if the grown box would overlap another placement or exceed " + BoxExpansion.MAX_VOLUME + " blocks.");

	private VcsCommandExpand() {
	}

	/**
	 * Grows the selected placement's box until only air surrounds it and saves the result as the build's next version.
	 * The commit is part of the expansion: the blocks the box just took in are not in any version yet, so without it
	 * the placement would count as modified right away and {@code /vcs checkout} would refuse.
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
			source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BuildBox box = placement.box();
		BoxExpansion expansion = BoxExpansion.of(box, level);
		if (!expansion.grew()) {
			if (expansion.enclosed()) {
				source.sendSuccess(() -> Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" is already enclosed by air; nothing to expand"), false);
			} else {
				source.sendFailure(Component.literal("Placement ").append(VcsMessages.placement(placement)).append(" touches blocks outside its box, but taking them in would grow it past the limit of "
					+ BoxExpansion.MAX_VOLUME + " blocks (it has " + box.volume() + " now); nothing was expanded"));
			}
			return 0;
		}
		// Every block belongs to at most one placement, so a grown box that reaches into another one is refused.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), expansion.to(), placement);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Expanding ").append(VcsMessages.placement(placement)).append(" to " + VcsMessages.size(expansion.to()))
				.append(" would overlap ").append(VcsMessages.placement(overlapping.get())).append("; placements may not intersect"));
			return 0;
		}

		BuildBox grown = expansion.to();
		Build build = placement.build().withNextVersion(grown.relativeTo(placement.origin()));
		int version = build.version();
		build = build.withPlacement(placement.name(), placement.placement().withHead(version));

		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Path file = BuildSaver.save(actor, session, build, version, grown, level);
			BuildRegistry.select(player, build, placement.name());

			source.sendSuccess(() -> Component.literal("Expanded ").append(VcsMessages.placement(placement)).append(" from " + VcsMessages.size(box) + " (" + box.volume() + " blocks) to "
				+ VcsMessages.size(grown) + " (" + grown.volume() + " blocks) and committed it as v" + version + " at " + BuildStorage.root().relativize(file)), false);
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to expand '{}' to v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}
}
