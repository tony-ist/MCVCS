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
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs expand}: grows the selected build's box until only air surrounds it, see {@link BoxExpansion}, so
 * whatever was built out past its edges is inside it again, and saves the box as the build's next version, which
 * keeps the latest schematic the size of the box. Refuses, and expands nothing, if the grown box would overlap another
 * build or exceed {@link BoxExpansion#MAX_VOLUME} blocks. Earlier versions keep their smaller size but are placed
 * inside the grown box where they were built, see {@link BoxSnapshot#ofClipboard}.
 */
public final class VcsCommandExpand {
	private VcsCommandExpand() {
	}

	/**
	 * Grows the selected build's box until only air surrounds it and saves the result as the build's next version.
	 * The commit is part of the expansion: the blocks the box just took in are not in any version yet, so without it
	 * the build would count as modified right away and {@code /vcs checkout} would refuse. Earlier versions stay the
	 * size they were; they are placed inside the grown box where they were built, see {@link BoxSnapshot#ofClipboard}.
	 */
	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}

		Build build = selected.get();
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BoxExpansion expansion = BoxExpansion.of(build.box(), level);
		if (!expansion.grew()) {
			if (expansion.enclosed()) {
				source.sendSuccess(() -> Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is already enclosed by air; nothing to expand"), false);
			} else {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" touches blocks outside its box, but taking them in would grow it past the limit of "
					+ BoxExpansion.MAX_VOLUME + " blocks (it has " + build.box().volume() + " now); nothing was expanded"));
			}
			return 0;
		}
		// Every block belongs to at most one build, so a grown box that reaches into another build is refused.
		Optional<Build> overlapping = BuildRegistry.all(source.getServer()).stream()
			.filter(other -> !other.name().equals(build.name()) && other.dimension().equals(build.dimension()) && other.box().intersects(expansion.to()))
			.findFirst();
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Expanding build ").append(VcsMessages.name(build.name())).append(" to " + VcsMessages.size(expansion.to()) + " would overlap build ").append(VcsMessages.name(overlapping.get().name())).append("; builds may not intersect"));
			return 0;
		}

		Build expanded = new Build(build.name(), build.world(), build.dimension(), expansion.to(), build.version() + 1);
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Path file = BuildSaver.save(actor, session, expanded, level);
			BuildRegistry.select(player, expanded);

			source.sendSuccess(() -> Component.literal("Expanded build ").append(VcsMessages.name(expanded.name())).append(" from " + VcsMessages.size(build.box()) + " (" + build.box().volume() + " blocks) to "
				+ VcsMessages.size(expanded.box()) + " (" + expanded.box().volume() + " blocks) and committed it as v" + expanded.version() + " at " + BuildStorage.root().relativize(file)), false);
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to expand build '{}' to v{} for {}", expanded.name(), expanded.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}
}
