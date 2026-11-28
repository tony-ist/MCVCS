package tony.mcvcs.command;

import java.io.IOException;
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
import tony.mcvcs.build.Placement;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs create <buildname> [placementname] [-b]}: turns the block the player clicks next, and everything connected
 * to it, into a build and saves it as version 1, see {@link PendingClick}; the click leaves the block alone. With
 * {@code -b} the player clicks two opposite corners instead, and the box between them is the build. The name
 * may not be that of any existing build in any world, ignoring case, and the box may not overlap any placement of any
 * build.
 * <p>
 * What is created is a build with one placement in it, called {@link Build#MAIN} unless another name is given. The
 * box becomes version 1's extent in build space, so the placement's origin is that box's minimum corner, and the
 * build's other placements, made later by {@code /vcs place}, measure from the same build space.
 */
public final class VcsCommandCreate {
	/** Flag that makes the build the box between two punched corners instead of what is connected to one block. */
	public static final String BOX = "-b";
	static final VcsHelp HELP = new VcsHelp("create", "/vcs create <buildname> [copyname] [-b]",
		"save something you built as a new build",
		"Saves your build as version 1 of a new build called <buildname>. After running it, hit any block of your build: everything touching that block is saved, so keep the build surrounded by air.",
		new VcsHelp.Key(BOX, "hit two opposite corners instead; everything between them is saved"));

	static final VcsHelp CANCEL_HELP = new VcsHelp("cancelCreate", "/vcs cancelCreate",
		"cancel `/vcs create`",
		"Stops waiting for you to hit a block; no build is created.");

	/** What a build or copy name has to look like, see {@link Build#isValidNewName}. */
	public static final String NAME_RULE = "start with a letter, and use only letters, digits, and _ + - .";

	private VcsCommandCreate() {
	}

	/** {@code /vcs cancelCreate}: disarms the player's click if {@code /vcs create} armed it. */
	static int cancel(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		String buildName = PendingClick.creating(player);
		if (buildName == null) {
			source.sendFailure(Component.literal("Nothing to cancel; you are not creating a build"));
			return 0;
		}
		PendingClick.disarm(player);
		source.sendSuccess(() -> Component.literal("Stopped creating build ").append(VcsMessages.name(buildName)), false);
		return 1;
	}

	/** Creates the build from the next block the player clicks, see {@link PendingClick}, replacing whatever an earlier command left waiting for a click. */
	static int run(CommandSourceStack source, String buildName, String placementName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.disarm(player);
		if (!isNameFree(source, buildName) || !isPlacementNameValid(source, placementName)) {
			return 0;
		}
		PendingClick.arm(player, Component.literal("Hit any block of your build to save it as ").append(VcsMessages.name(buildName)), null, buildName,
			(clicker, level, pos) -> createFromBlock(clicker, level, buildName, placementName, pos));
		source.sendSuccess(() -> Component.literal("Hit any block of your build, or right-click it with an empty hand, to save it as ")
			.append(VcsMessages.name(buildName)).append("; everything touching that block is saved"), false);
		return 1;
	}

	/**
	 * {@code /vcs create <buildname> [placementname] -b}: creates the build from the box between the next two blocks
	 * the player clicks, see {@link PendingClick}, replacing whatever an earlier command left waiting for a click.
	 */
	static int runBox(CommandSourceStack source, String buildName, String placementName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PendingClick.disarm(player);
		if (!isNameFree(source, buildName) || !isPlacementNameValid(source, placementName)) {
			return 0;
		}
		PendingClick.arm(player, Component.literal("Hit one corner of ").append(VcsMessages.name(buildName)), null, buildName,
			(clicker, level, first) -> armSecondCorner(clicker, level, buildName, placementName, first));
		source.sendSuccess(() -> Component.literal("Hit two opposite corners, or right-click them with an empty hand, to save everything between them as ")
			.append(VcsMessages.name(buildName)), false);
		return 1;
	}

	/** Waits for the second corner of the box whose first corner the player clicked at {@code first} in {@code firstLevel}. */
	private static void armSecondCorner(ServerPlayer player, ServerLevel firstLevel, String buildName, String placementName, BlockPos first) {
		PendingClick.arm(player, Component.literal("Now hit the opposite corner of ").append(VcsMessages.name(buildName)), first, buildName, (clicker, level, second) -> {
			CommandSourceStack source = clicker.createCommandSourceStack();
			if (level != firstLevel) {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" was not created: its corners are in different dimensions"));
				return;
			}
			BuildBox box = new BuildBox(first, first).union(new BuildBox(second, second));
			if (box.volume() > BoxExpansion.MAX_VOLUME) {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" was not created: its box of " + box.volume()
					+ " blocks is over the limit of " + BoxExpansion.MAX_VOLUME + " blocks"));
				return;
			}
			// Checked again, since another player may have used the name while the corners were waited for.
			if (isNameFree(source, buildName)) {
				create(source, clicker, buildName, placementName, level, box);
			}
		});
	}

	/**
	 * Creates the build called {@code buildName} with one placement covering exactly {@code box} in {@code level},
	 * checking the names as {@code /vcs create} does and telling {@code player} how it went. For callers that already
	 * know the box, such as the game tests.
	 *
	 * @return 1 if the build was created, 0 otherwise
	 */
	public static int createFromBox(ServerPlayer player, ServerLevel level, String buildName, String placementName, BuildBox box) {
		CommandSourceStack source = player.createCommandSourceStack();
		if (!isNameFree(source, buildName) || !isPlacementNameValid(source, placementName)) {
			return 0;
		}
		return create(source, player, buildName, placementName, level, box);
	}

	/**
	 * Creates the build called {@code buildName} from the block at {@code pos} in {@code level}, the world
	 * {@code player} clicked it in: the box is the one block grown until only air surrounds it, see {@link BoxExpansion},
	 * so it takes in everything connected to the block, the way {@code /vcs fit} would. Nothing is created if that
	 * would go past {@link BoxExpansion#MAX_VOLUME} blocks.
	 * The name is checked again here, since another player may have used it while the click was waited for.
	 */
	static void createFromBlock(ServerPlayer player, ServerLevel level, String buildName, String placementName, BlockPos pos) {
		CommandSourceStack source = player.createCommandSourceStack();
		if (!isNameFree(source, buildName)) {
			return;
		}
		BoxExpansion expansion = BoxExpansion.of(new BuildBox(pos, pos), level);
		if (!expansion.enclosed()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" was not created: the blocks touching " + pos.toShortString()
				+ " go past the limit of " + BoxExpansion.MAX_VOLUME + " blocks; surround your build with air so it touches nothing else"));
			return;
		}
		create(source, player, buildName, placementName, level, expansion.to());
	}

	/**
	 * Whether no build may be called {@code buildName}: the name is well-formed and not that of an existing build,
	 * compared without regard to case; the source is told why otherwise.
	 */
	private static boolean isNameFree(CommandSourceStack source, String buildName) {
		if (!isNameValid(source, buildName)) {
			return false;
		}
		// A build is created once and committed to after that; creating it again would throw its versions away. Builds
		// are shared by every world in the game directory, so the name may be taken by one created in another world, and
		// the file system may not tell two names apart by case, so neither does the check.
		Optional<Build> taken = BuildRegistry.findIgnoringCase(source.getServer(), buildName);
		if (taken.isPresent()) {
			Build existing = taken.get();
			if (existing.placements().isEmpty()) {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(existing.name())).append(" already exists; put it in this world with ").append(ChatButtons.command("/vcs place " + existing.name())).append(", or choose another name"));
			} else {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(existing.name())).append(" already exists; select it with ").append(ChatButtons.command("/vcs select " + existing.name())).append(", or choose another name"));
			}
			return false;
		}
		return true;
	}

	/** Whether a new build may be called {@code buildName}, taken or not; the source is told why otherwise. */
	static boolean isNameValid(CommandSourceStack source, String buildName) {
		// The name becomes a folder on disk, so it has to be checked before anything is written under it.
		if (!Build.isValidNewName(buildName)) {
			source.sendFailure(Component.literal("Build name ").append(VcsMessages.name(buildName)).append(" is not allowed: " + NAME_RULE));
			return false;
		}
		return true;
	}

	/** Whether {@code placementName} is well-formed; the source is told why otherwise. */
	static boolean isPlacementNameValid(CommandSourceStack source, String placementName) {
		if (!Build.isValidNewName(placementName)) {
			source.sendFailure(Component.literal("Copy name ").append(VcsMessages.name(placementName)).append(" is not allowed: " + NAME_RULE));
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
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" would overlap ").append(VcsMessages.placement(overlapping.get())).append(VcsMessages.NO_OVERLAP));
			return 0;
		}
		Build build = placement.applied();

		try {
			BuildSaver.save(player, build, 1, box, level);
			BuildRegistry.select(player, build, placementName);

			source.sendSuccess(() -> Component.literal("Created build ").append(VcsMessages.name(buildName))
				.append(" v1 (" + VcsMessages.size(box) + ", " + box.volume() + " blocks)"), false);
			return 1;
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to create build '{}' from {} for {}", buildName, box, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save: " + e.getMessage()));
			return 0;
		}
	}
}
