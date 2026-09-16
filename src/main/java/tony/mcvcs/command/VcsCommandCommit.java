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
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.ChatButtons;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs commit}: saves the selected build's box again as its next version. The box is the one captured by
 * {@code /vcs create}; the player's current WorldEdit selection is ignored. If anything other than air touches the
 * box, the version is saved all the same but a yellow warning points the player at {@code /vcs expand}.
 */
public final class VcsCommandCommit {
	private VcsCommandCommit() {
	}

	/** What the command says, in yellow, after committing a build that has blocks touching its box. */
	public static MutableComponent notEnclosedWarning() {
		return Component.literal("Warning: the build is not enclosed by air, so blocks touching its box were left out; run ")
			.append(ChatButtons.command("/vcs expand"))
			.append(" to expand the build area")
			.withStyle(ChatFormatting.YELLOW);
	}

	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}

		Build build = selected.get().nextVersion();
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		// Blocks touching the box are probably part of the build and are about to be left out of the version.
		boolean enclosed = BoxExpansion.isEnclosed(build.box(), level);

		try {
			Path file = BuildSaver.save(actor, session, build, level);
			BuildRegistry.select(player, build);

			source.sendSuccess(() -> Component.literal("Committed build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " (" + build.box().volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
			if (!enclosed) {
				source.sendSuccess(VcsCommandCommit::notEnclosedWarning, false);
			}
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to commit build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}
}
