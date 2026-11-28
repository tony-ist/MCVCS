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
 * and naming the placement as well always does. Either way, a click an earlier command left waiting for is dropped,
 * whether or not this one then waits for a click of its own, and even when it fails.
 */
public final class VcsCommandSelect {
	static final VcsHelp HELP = new VcsHelp("select", "/vcs select [buildname [copyname]]",
		"choose which build to work on",
		"Selects a build, so `/vcs save`, `/vcs load` and the other commands work on it; it gets an outline in the world. With no name, hit a block of the build; the block is not broken. If the build has several copies, hit the one you want, or name it too.");

	private VcsCommandSelect() {
	}

	/** {@code /vcs select}: the placement of whichever block the player punches next. */
	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.arm(player, Component.literal("Hit a block of a build to select it"), VcsCommandSelect::selectAt);
		source.sendSuccess(() -> Component.literal("Hit a block of a build, or right-click it with an empty hand, to select it"), false);
		return 1;
	}

	/**
	 * {@code /vcs select <buildname>}: the build's only placement, or, when it has several, the one the player
	 * punches next.
	 */
	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.disarm(player);
		Optional<Build> found = BuildRegistry.find(source.getServer(), buildName);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append("; create it with ").append(ChatButtons.command("/vcs create " + buildName)));
			return 0;
		}

		Build build = found.get();
		if (build.placements().isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" is not in this world; run ").append(ChatButtons.command("/vcs place " + buildName)).append(" to put a copy here"));
			return 0;
		}
		if (build.placements().size() > 1) {
			PendingClick.arm(player, Component.literal("Hit a block of ").append(VcsMessages.name(buildName)).append(" to select it"),
				VcsCommandSelect::selectAt);
			source.sendSuccess(() -> Component.literal("Build ").append(VcsMessages.name(buildName)).append(" has " + build.placements().size()
				+ " copies (" + String.join(", ", build.placementNames()) + "); hit a block of the one you want"), false);
			return 1;
		}
		return select(source, player, new BuildPlacement(build, build.placementNames().getFirst(), build.placements().values().iterator().next()));
	}

	/** {@code /vcs select <buildname> <placementname>}: that placement, with nothing to punch. */
	static int run(CommandSourceStack source, String buildName, String placementName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.disarm(player);
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append("; create it with ").append(ChatButtons.command("/vcs create " + buildName)));
			return 0;
		}
		Optional<BuildPlacement> placement = BuildPlacement.of(build.get(), placementName);
		if (placement.isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" has no copy ").append(VcsMessages.name(placementName))
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
			source.sendFailure(Component.literal("No build at " + pos.toShortString() + "; run ")
				.append(ChatButtons.command("/vcs select")).append(" to try another block"));
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
			.append(" (v" + placement.head() + " of " + placement.build().version() + ", " + placement.box().volume() + " blocks)"), false);
		return 1;
	}
}
