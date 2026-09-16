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
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs tp [buildname]}: teleports the player onto the top of a build's box, at its centre, in whatever
 * dimension the build is in. Without a name it goes to the selected build.
 */
public final class VcsCommandTp {
	private VcsCommandTp() {
	}

	/** Teleports the player on top of the build named {@code buildName}. */
	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world; see ").append(ChatButtons.command("/vcs builds")));
			return 0;
		}
		return teleport(source, player, build.get());
	}

	/** Teleports the player on top of the selected build. */
	static int runSelected(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}
		return teleport(source, player, selected.get());
	}

	/** Puts the player's feet on the block layer above the box's top, centred on it, keeping the way they are facing. */
	private static int teleport(CommandSourceStack source, ServerPlayer player, Build build) {
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BuildBox box = build.box();
		double x = box.min().getX() + box.sizeX() / 2.0;
		double y = box.max().getY() + 1;
		double z = box.min().getZ() + box.sizeZ() / 2.0;
		player.teleportTo(level, x, y, z, Relative.ROTATION, 0, 0, true);
		source.sendSuccess(() -> Component.literal("Teleported on top of build ").append(VcsMessages.name(build.name())), false);
		return 1;
	}
}
