package tony.mcvcs.command;

import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs tp [buildname [placementname]]}: teleports the player onto the top of a placement's box, at its centre,
 * in whatever dimension that placement is in. Without a placement name it takes the build's {@link Build#MAIN} one,
 * or its first if that is gone; without a name at all it goes to the selected placement.
 */
public final class VcsCommandTp {
	static final VcsHelp HELP = new VcsHelp("tp", "/vcs tp [buildname [copyname]]",
		"teleport to a build",
		"Teleports you on top of the build. Given only a build name, goes to its " + Build.MAIN + " copy; given nothing, to your selected build.");

	private VcsCommandTp() {
	}

	/** Teleports the player onto the {@link Build#MAIN} placement of the build named {@code buildName}. */
	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		Optional<Build> build = find(source, buildName);
		if (build.isEmpty()) {
			return 0;
		}
		Optional<String> placement = build.get().defaultPlacementName();
		if (placement.isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" is not in this world; run ")
				.append(ChatButtons.command("/vcs place " + buildName)).append(" to put a copy here"));
			return 0;
		}
		return run(source, buildName, placement.get());
	}

	/** Teleports the player onto the named placement of the build named {@code buildName}. */
	static int run(CommandSourceStack source, String buildName, String placementName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = find(source, buildName);
		if (build.isEmpty()) {
			return 0;
		}
		Optional<BuildPlacement> placement = BuildPlacement.of(build.get(), placementName);
		if (placement.isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" has no copy ").append(VcsMessages.name(placementName))
				.append("; it has " + (build.get().placements().isEmpty() ? "none" : String.join(", ", build.get().placementNames()))));
			return 0;
		}
		return teleport(source, player, placement.get());
	}

	/** Teleports the player onto their selected placement. */
	static int runSelected(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}
		return teleport(source, player, selected.get());
	}

	/** The build called {@code buildName} in this world; the source is told when there is none. */
	private static Optional<Build> find(CommandSourceStack source, String buildName) {
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append("; see ").append(ChatButtons.command("/vcs builds")));
		}
		return build;
	}

	/** Puts the player's feet on the block layer above the box's top, centred on it, keeping the way they are facing. */
	private static int teleport(CommandSourceStack source, ServerPlayer player, BuildPlacement placement) {
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			source.sendFailure(VcsMessages.missingDimension(placement));
			return 0;
		}

		BuildBox box = placement.box();
		double x = box.min().getX() + box.sizeX() / 2.0;
		double y = box.max().getY() + 1;
		double z = box.min().getZ() + box.sizeZ() / 2.0;
		player.teleportTo(level, x, y, z, Relative.ROTATION, 0, 0, true);
		source.sendSuccess(() -> Component.literal("Teleported on top of ").append(VcsMessages.placement(placement)), false);
		return 1;
	}
}
