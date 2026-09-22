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

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.ChatButtons;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs commit}: saves what is inside the selected placement's box as the build's next version. The box is the
 * one the placement holds, not the player's WorldEdit selection. If anything other than air is touching the box, the
 * version is saved all the same but a yellow warning points the player at {@code /vcs expand}.
 * <p>
 * Versions belong to the build, not to the placement they were committed from: every other placement of the build
 * can check the new version out, wherever it stands. The committing placement is the only one whose head moves, so
 * the others go on holding whatever version they held.
 */
public final class VcsCommandCommit {
	static final VcsHelp HELP = new VcsHelp("commit", "/vcs commit",
		"save the selected placement as the build's next version",
		"Saves what is inside the selected placement's box as the build's next version. WorldEdit selection does not matter, only the placement's box is concerned. Every other placement of the build can then check that version out. If anything other than air touches the box, the version is saved all the same but you are warned to run /vcs expand, since those blocks were left out.");

	private VcsCommandCommit() {
	}

	/** What the command says, in yellow, after committing a placement that has blocks touching its box. */
	public static MutableComponent notEnclosedWarning() {
		return Component.literal("Warning: the build is not enclosed by air, so blocks touching its box were left out; run ")
			.append(ChatButtons.command("/vcs expand"))
			.append(" to expand the build area")
			.withStyle(ChatFormatting.YELLOW);
	}

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

		// The box does not change on a commit, so the new version covers exactly what the placement holds now.
		BuildBox box = placement.box();
		BuildBox extent = placement.build().extent(placement.head());
		Build build = placement.build().withNextVersion(extent);
		int version = build.version();
		build = build.withPlacement(placement.name(), placement.placement().withHead(version));

		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		// Blocks touching the box are probably part of the build and are about to be left out of the version.
		boolean enclosed = BoxExpansion.isEnclosed(box, level);

		try {
			Path file = BuildSaver.save(actor, session, build, version, box, level);
			BuildRegistry.select(player, build, placement.name());

			source.sendSuccess(() -> Component.literal("Committed ").append(VcsMessages.placement(placement)).append(" as build ")
				.append(VcsMessages.name(placement.build().name())).append(" v" + version + " (" + box.volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
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
}
