package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
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
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.Placement;
import tony.mcvcs.network.ChatButtons;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;

/**
 * {@code /vcs create <buildname> [placementname] [-we]}: turns the block the player clicks next, and everything
 * connected to it, into a build and saves it as version 1, see {@link PendingClick}; the click leaves the block
 * alone. With {@link #SELECTION} the bounding box of their WorldEdit selection is taken instead, and no click is
 * waited for. The name may not be that of any existing build in any world, ignoring case, and the box may not overlap
 * any placement of any build.
 * <p>
 * What is created is a build with one placement in it, called {@link Build#MAIN} unless another name is given. The
 * box becomes version 1's extent in build space, so the placement's origin is that box's minimum corner, and the
 * build's other placements, made later by {@code /vcs place}, measure from the same build space.
 */
public final class VcsCommandCreate {
	/** Flag after {@code /vcs create} that takes the player's WorldEdit selection as the box instead of a click. */
	public static final String SELECTION = "-we";

	static final VcsHelp HELP = new VcsHelp("create", "/vcs create <buildname> [placementname] [" + SELECTION + "]",
		"start a build from the block you punch",
		"Starts a build called <buildname> and saves it as version 1. Punch any block of the build: the selection grows over everything connected to that block, so it should hover in the air, touching nothing that is not part of it. Add " + SELECTION + " to use the bounding box of your WorldEdit selection as the build instead.");

	private VcsCommandCreate() {
	}

	/**
	 * Creates the build from the next block the player clicks, see {@link PendingClick}, or, with
	 * {@code useSelection}, from the bounding box of their WorldEdit selection. Either way this command replaces
	 * whatever an earlier one left waiting for a click.
	 */
	static int run(CommandSourceStack source, String buildName, String placementName, boolean useSelection) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.disarm(player);
		if (!isNameFree(source, buildName) || !isPlacementNameValid(source, placementName)) {
			return 0;
		}
		if (!useSelection) {
			PendingClick.arm(player, (clicker, level, pos) -> createFromBlock(clicker, level, buildName, placementName, pos));
			source.sendSuccess(() -> Component.literal("Punch a block of the build, or right-click it with an empty hand, to create build ")
				.append(VcsMessages.name(buildName)).append(" from it and everything connected to it"), false);
			return 1;
		}
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		BuildBox box;
		try {
			// Only the bounding box is kept, so later //pos1, //pos2 or wand clicks cannot move the build's box under us.
			box = BuildBox.of(session.getSelection(actor.getWorld()));
		} catch (IncompleteRegionException e) {
			source.sendFailure(Component.literal("No WorldEdit selection to create build ").append(VcsMessages.name(buildName))
				.append(" from; make one with the wand, or run ").append(ChatButtons.command("/vcs create " + buildName)).append(" and punch a block of the build"));
			return 0;
		}
		return create(source, player, buildName, placementName, player.level(), box);
	}

	/**
	 * Creates the build called {@code buildName} from the block at {@code pos} in {@code level}, the world
	 * {@code player} clicked it in: the box is the one block grown until only air surrounds it, see {@link BoxExpansion},
	 * so it takes in everything connected to the block, the way {@code /vcs expand} would. Nothing is created if that
	 * would go past {@link BoxExpansion#MAX_VOLUME} blocks; the player is told to select the build with WorldEdit instead.
	 * The name is checked again here, since another player may have used it while the click was waited for.
	 */
	static void createFromBlock(ServerPlayer player, ServerLevel level, String buildName, String placementName, BlockPos pos) {
		CommandSourceStack source = player.createCommandSourceStack();
		if (!isNameFree(source, buildName)) {
			return;
		}
		BoxExpansion expansion = BoxExpansion.of(new BuildBox(pos, pos), level);
		if (!expansion.enclosed()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" was not created: the blocks connected to " + pos.toShortString()
				+ " reach past the limit of " + BoxExpansion.MAX_VOLUME + " blocks; select the build with WorldEdit and run ").append(ChatButtons.command("/vcs create " + buildName + " " + placementName + " " + SELECTION)).append(" instead"));
			return;
		}
		create(source, player, buildName, placementName, level, expansion.to());
	}

	/**
	 * Whether no build may be called {@code buildName}: the name is well-formed and not that of an existing build,
	 * compared without regard to case; the source is told why otherwise.
	 */
	private static boolean isNameFree(CommandSourceStack source, String buildName) {
		// The name becomes a folder on disk, so it has to be checked before anything is written under it.
		if (!Build.isValidName(buildName)) {
			source.sendFailure(Component.literal("Build name ").append(VcsMessages.name(buildName)).append(" may only contain letters, digits, _ + - and dots between them, and may not start with -"));
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

	/** Whether {@code placementName} is well-formed; the source is told why otherwise. */
	static boolean isPlacementNameValid(CommandSourceStack source, String placementName) {
		if (!Build.isValidName(placementName)) {
			source.sendFailure(Component.literal("Placement name ").append(VcsMessages.name(placementName)).append(" may only contain letters, digits, _ + - and dots between them, and may not start with -"));
			return false;
		}
		return true;
	}

	/**
	 * Creates the build called {@code buildName}, whose name {@link #isNameFree} has passed, with one placement
	 * covering {@code box} in {@code level}, saves the box as its version 1 and makes it the player's selected placement.
	 */
	private static int create(CommandSourceStack source, ServerPlayer player, String buildName, String placementName, ServerLevel level, BuildBox box) {
		// Build space starts at version 1's minimum corner, so the first placement's origin is where that corner sits.
		BuildPlacement placement = new BuildPlacement(
			new Build(buildName, Build.worldOf(source.getServer()), 1, Map.of(1, box.relativeTo(box.min())), Map.of()),
			placementName, new Placement(level.dimension(), box.min(), 1));
		// Every block belongs to at most one placement, so a box that overlaps one in this dimension is refused.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), box, null);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" would overlap ").append(VcsMessages.placement(overlapping.get())).append("; placements may not intersect"));
			return 0;
		}
		Build build = placement.applied();
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Path file = BuildSaver.save(actor, session, build, 1, box, level);
			BuildRegistry.select(player, build, placementName);

			source.sendSuccess(() -> Component.literal("Created build ").append(VcsMessages.name(buildName)).append(" as placement ").append(VcsMessages.name(placementName))
				.append(" (" + VcsMessages.size(box) + ", " + box.volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to create build '{}' from {} for {}", buildName, box, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}
}
