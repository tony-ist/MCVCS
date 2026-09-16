package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
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
 * {@code /vcs create <buildname>}: copies the bounding box of the player's current WorldEdit selection and saves it as
 * version 1 of the build, in the build's own folder under {@code mcvcs/} in the game directory, see
 * {@link BuildStorage}. Without a selection, the build is made from the next block the player punches or right-clicks
 * with an empty hand, grown over everything connected to it as {@code /vcs expand} would grow it, see
 * {@link CreateOnClick}; the click leaves the block alone. The name may not be that of any existing build in any
 * world, compared without regard to case, and the box may not overlap any existing build in the same dimension. The
 * new build becomes the player's selected build.
 */
public final class VcsCommandCreate {
	private VcsCommandCreate() {
	}

	/**
	 * Creates the build from the bounding box of the player's WorldEdit selection, or, when they have none, from the
	 * next block they click, see {@link CreateOnClick}. Either way this command replaces whatever an earlier one left
	 * waiting for a click.
	 */
	static int run(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		CreateOnClick.disarm(player);
		if (!isNameFree(source, buildName)) {
			return 0;
		}
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		BuildBox box;
		try {
			// Only the bounding box is kept, so later //pos1, //pos2 or wand clicks cannot move the build's box under us.
			box = BuildBox.of(session.getSelection(actor.getWorld()));
		} catch (IncompleteRegionException e) {
			CreateOnClick.arm(player, buildName);
			source.sendSuccess(() -> Component.literal("No WorldEdit selection; punch a block of the build, or right-click it with an empty hand, to create build ")
				.append(VcsMessages.name(buildName)).append(" from it and everything connected to it"), false);
			return 1;
		}
		return create(source, player, buildName, player.level(), box);
	}

	/**
	 * Creates the build called {@code buildName} from the block at {@code pos} in {@code level}, the world
	 * {@code player} clicked it in: the box is the one block grown until only air surrounds it, see {@link BoxExpansion},
	 * so it takes in everything connected to the block, the way {@code /vcs expand} would. Nothing is created if that
	 * would go past {@link BoxExpansion#MAX_VOLUME} blocks; the player is told to select the build with WorldEdit instead.
	 * The name is checked again here, since another player may have used it while the click was waited for.
	 */
	static void createFromBlock(ServerPlayer player, ServerLevel level, String buildName, BlockPos pos) {
		CommandSourceStack source = player.createCommandSourceStack();
		if (!isNameFree(source, buildName)) {
			return;
		}
		BoxExpansion expansion = BoxExpansion.of(new BuildBox(pos, pos), level);
		if (!expansion.enclosed()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" was not created: the blocks connected to " + pos.toShortString()
				+ " reach past the limit of " + BoxExpansion.MAX_VOLUME + " blocks; select the build with WorldEdit and run ").append(ChatButtons.command("/vcs create " + buildName)).append(" again"));
			return;
		}
		create(source, player, buildName, level, expansion.to());
	}

	/**
	 * Whether no build may be called {@code buildName}: the name is well-formed and not that of an existing build,
	 * compared without regard to case; the source is told why otherwise.
	 */
	private static boolean isNameFree(CommandSourceStack source, String buildName) {
		// The name becomes a folder on disk, so it has to be checked before anything is written under it.
		if (!Build.isValidName(buildName)) {
			source.sendFailure(Component.literal("Build name ").append(VcsMessages.name(buildName)).append(" may only contain letters, digits, _ + - and dots between them"));
			return false;
		}
		// A build is created once and committed to after that; creating it again would throw its versions away. Build
		// folders are shared by every world in the game directory, so a name can only belong to one world, and the file
		// system may not tell two names apart by case, so neither does the check.
		Optional<Build> taken = BuildRegistry.findInAnyWorldIgnoringCase(buildName);
		if (taken.isPresent()) {
			Build existing = taken.get();
			if (existing.world().equals(Build.worldOf(source.getServer()))) {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(existing.name())).append(" already exists in this world; select it with ").append(ChatButtons.command("/vcs select " + existing.name())).append(", or choose another name"));
			} else {
				source.sendFailure(Component.literal("Build name ").append(VcsMessages.name(existing.name())).append(" is already used by a build in world '" + existing.world() + "'"));
			}
			return false;
		}
		return true;
	}

	/**
	 * Creates the build called {@code buildName}, whose name {@link #isNameFree} has passed, covering {@code box} in
	 * {@code level}, saves the box as its version 1 and makes it the player's selected build.
	 */
	private static int create(CommandSourceStack source, ServerPlayer player, String buildName, ServerLevel level, BuildBox box) {
		Build build = new Build(buildName, Build.worldOf(source.getServer()), level.dimension(), box, 1);
		// Every block belongs to at most one build, so a box that overlaps an existing build in this dimension is refused.
		Optional<Build> overlapping = BuildRegistry.all(source.getServer()).stream()
			.filter(other -> other.dimension().equals(build.dimension()) && other.box().intersects(build.box()))
			.findFirst();
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" would overlap build ").append(VcsMessages.name(overlapping.get().name())).append("; builds may not intersect"));
			return 0;
		}
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Path file = BuildSaver.save(actor, session, build, level);
			BuildRegistry.select(player, build);

			source.sendSuccess(() -> Component.literal("Created build ").append(VcsMessages.name(buildName)).append(" (" + VcsMessages.size(box) + ", " + box.volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to create build '{}' from {} for {}", buildName, box, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}
}
