package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs select [buildname [placementname]]}: picks the placement every other command then acts on, and whose
 * box the client draws.
 * <p>
 * A build may stand in the world several times over, so a name alone does not always say which copy is meant. With
 * no arguments, or with a build that has more than one placement, the command asks for a block to be punched
 * instead, see {@link PendingClick}: the block is left alone and the placement whose box it is inside becomes the
 * selected one, whichever build that placement belongs to. A build with a single placement selects it straight away,
 * and naming the placement as well always does.
 */
public final class VcsCommandSelect {
	static final VcsHelp HELP = new VcsHelp("select", "/vcs select [buildname [placementname]]",
		"select a placement to work on",
		"Selects the placement that later commands act on and whose box is drawn. With no arguments, punch a block of the placement you want: the block is left alone. With a build that has one placement, that one is selected; with a build that has several, punch one of them. With a placement name too, that placement is selected at once.");

	private VcsCommandSelect() {
	}

	/** {@code /vcs select}: the placement of whichever block the player punches next. */
	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.arm(player, VcsCommandSelect::selectAt);
		source.sendSuccess(() -> Component.literal("Punch a block of a placement, or right-click it with an empty hand, to select it"), false);
		return 1;
	}

	/**
	 * {@code /vcs select <buildname>}: the build's only placement, or, when it has several, the one the player
	 * punches next.
	 */
	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> found = BuildRegistry.find(source.getServer(), buildName);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world; create it with ").append(ChatButtons.command("/vcs create " + buildName)));
			return 0;
		}

		Build build = found.get();
		if (build.placements().isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" is not placed anywhere; run ").append(ChatButtons.command("/vcs place " + buildName)).append(" to put it in the world"));
			return 0;
		}
		if (build.placements().size() > 1) {
			PendingClick.arm(player, VcsCommandSelect::selectAt);
			source.sendSuccess(() -> Component.literal("Build ").append(VcsMessages.name(buildName)).append(" has " + build.placements().size()
				+ " placements (" + String.join(", ", build.placementNames()) + "); punch a block of the one you want to select it"), false);
			return 1;
		}
		return select(source, player, new BuildPlacement(build, build.placementNames().getFirst(), build.placements().values().iterator().next()));
	}

	/** {@code /vcs select <buildname> <placementname>}: that placement, with nothing to punch. */
	static int run(CommandSourceStack source, String buildName, String placementName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world; create it with ").append(ChatButtons.command("/vcs create " + buildName)));
			return 0;
		}
		Optional<BuildPlacement> placement = BuildPlacement.of(build.get(), placementName);
		if (placement.isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" has no placement ").append(VcsMessages.name(placementName))
				.append("; it has " + (build.get().placements().isEmpty() ? "none" : String.join(", ", build.get().placementNames()))));
			return 0;
		}
		return select(source, player, placement.get());
	}

	/**
	 * The placement whose box holds the punched block, whichever build it belongs to; boxes never overlap, so at most
	 * one can. A block that is in no placement selects nothing and the player is told so, having already been disarmed.
	 */
	private static void selectAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
		CommandSourceStack source = player.createCommandSourceStack();
		Optional<BuildPlacement> placement = BuildRegistry.at(level.getServer(), level.dimension(), pos);
		if (placement.isEmpty()) {
			source.sendFailure(Component.literal("No placement covers " + pos.toShortString() + "; nothing was selected, run ")
				.append(ChatButtons.command("/vcs select")).append(" to punch another block"));
			return;
		}
		select(source, player, placement.get());
	}

	/** Records the selection and says so, whether it came from a name or from a punch. */
	private static int select(CommandSourceStack source, ServerPlayer player, BuildPlacement placement) {
		try {
			BuildRegistry.select(player, placement);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to select '{}' for {}", placement.label(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save selection: " + e.getMessage()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Selected ").append(VcsMessages.placement(placement))
			.append(" at v" + placement.head() + " of " + placement.build().version() + " (" + placement.box().volume() + " blocks)"), false);
		return 1;
	}
}
