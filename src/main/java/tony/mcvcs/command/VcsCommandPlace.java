package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.Placement;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.network.ChatButtons;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

/**
 * {@code /vcs place <buildname> [version | latest] [placementname] [-f]}: puts another copy of a build into the
 * world where the player stands, as a placement of its own.
 * <p>
 * The copy lands the way {@code /vcs load} and {@code //paste} would put it: its top north-west corner one block
 * below the player's feet, so the build hangs below them, extending east and south. That fixes the placement's
 * {@link Placement#origin} for good; checking out another version there afterwards grows or shrinks its box around
 * the build rather than moving it.
 * <p>
 * The new placement lives its own life from then on: it holds the version it was placed at, can be modified and
 * checked out on its own, and a commit from it saves the build's next version like a commit from any other.
 * Placements may never overlap, and blocks already standing in the way are refused unless {@link VcsCommand#FORCE}
 * is given, which overwrites them for good.
 */
public final class VcsCommandPlace {
	static final VcsHelp HELP = new VcsHelp("place", "/vcs place <buildname> [version | latest] [placementname] [" + VcsCommand.FORCE + "]",
		"put another copy of a build into the world",
		"Puts that version of the build, or its latest one, into the world where you stand, as a new placement with its own name (" + Build.PLACEMENT_PREFIX + "2, " + Build.PLACEMENT_PREFIX + "3 and so on unless you name it). It lands one block below your feet, extending east and south, and becomes your selected placement. The new placement lives its own life: check it out and modify it on its own, and commits from it become versions of the same build. Refuses if it would overlap another placement, or if anything is standing in the way unless you add " + VcsCommand.FORCE + ", which overwrites those blocks for good.");

	private VcsCommandPlace() {
	}

	/**
	 * @param version       the version to place, or {@link VcsCommand#LATEST} for the build's latest one
	 * @param placementName the name for the new placement, or null for the build's next free one
	 * @param force         whether to overwrite blocks standing where the copy goes instead of refusing
	 */
	static int run(CommandSourceStack source, String buildName, int version, String placementName, boolean force) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> found = BuildRegistry.find(source.getServer(), buildName);
		if (found.isEmpty()) {
			source.sendFailure(Component.literal("No build named ").append(VcsMessages.name(buildName)).append(" in this world; see ").append(ChatButtons.command("/vcs builds")));
			return 0;
		}

		Build build = found.get();
		if (version > build.version()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" only has versions 1 to " + build.version()));
			return 0;
		}
		int placed = version == VcsCommand.LATEST ? build.version() : version;
		String name = placementName == null ? build.freePlacementName() : placementName;
		if (!VcsCommandCreate.isPlacementNameValid(source, name)) {
			return 0;
		}
		if (build.placement(name).isPresent()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(buildName)).append(" already has a placement called ").append(VcsMessages.name(name)));
			return 0;
		}

		ServerLevel level = player.level();
		BuildBox extent = build.extent(placed);
		BuildBox box = atFeet(extent, player.blockPosition());
		BuildPlacement placement = new BuildPlacement(build, name, new Placement(level.dimension(), box.min().subtract(extent.min()), placed));

		// Every block belongs to at most one placement, whatever is standing there.
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), level.dimension(), box, null);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Placing build ").append(VcsMessages.name(buildName)).append(" here would overlap ")
				.append(VcsMessages.placement(overlapping.get())).append("; placements may not intersect"));
			return 0;
		}
		// Blocks that are in the way are not part of any build, so they are only overwritten when asked for.
		int inTheWay = nonAir(box, level);
		if (inTheWay > 0 && !force) {
			source.sendFailure(Component.literal("Placing build ").append(VcsMessages.name(buildName)).append(" here would overwrite " + inTheWay
				+ (inTheWay == 1 ? " block" : " blocks") + " already standing in its " + VcsMessages.size(box) + " box at " + box.min().toShortString()
				+ "; move somewhere clear or add " + VcsCommand.FORCE + " to overwrite them"));
			return 0;
		}

		Clipboard clipboard;
		try {
			clipboard = BuildStorage.readSchematic(buildName, placed);
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(buildName)).append(" v" + placed + " at " + e.getFile()));
			return 0;
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to read build '{}' v{} for {}", buildName, placed, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}

		try {
			BuildPlacer.place(player, level, List.of(box), clipboard, box);
		} catch (WorldEditException e) {
			MCVCS.LOGGER.error("Failed to place build '{}' v{} for {}", buildName, placed, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to place schematic: " + e.getMessage()));
			return 0;
		}

		Build updated = placement.applied();
		try {
			BuildStorage.update(updated);
			// The placement is new to every client, not just to the one that made it.
			BuildSync.broadcast(source.getServer());
			BuildRegistry.select(player, updated, name);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to record placement '{}' of build '{}' for {}", name, buildName, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Placed build ").append(VcsMessages.name(buildName)).append(" but failed to record the placement: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} placed build '{}' v{} as placement '{}' at {} in {}, overwriting {} blocks",
			player.getGameProfile().name(), buildName, placed, name, box.min().toShortString(), level.dimension().identifier(), inTheWay);

		source.sendSuccess(() -> {
			MutableComponent message = Component.literal("Placed ").append(VcsMessages.name(buildName + Build.LABEL_SEPARATOR + name))
				.append(" v" + placed + " (" + VcsMessages.size(box) + ", " + box.volume() + " blocks) at " + box.min().toShortString());
			if (inTheWay > 0) {
				// Only a forced placement gets here; those blocks are gone, and not into WorldEdit's history either.
				message.append(", overwriting " + inTheWay + (inTheWay == 1 ? " block" : " blocks") + " that stood there");
			}
			return message;
		}, false);
		return 1;
	}

	/**
	 * Where a version of extent {@code extent} lands for a player standing at {@code feet}: the same place
	 * {@code /vcs load} followed by {@code //paste} would put it, with its top north-west corner one block below
	 * their feet, so the build hangs below them and extends east and south.
	 */
	static BuildBox atFeet(BuildBox extent, BlockPos feet) {
		BlockPos min = new BlockPos(feet.getX(), feet.getY() - extent.sizeY(), feet.getZ());
		return new BuildBox(min, min.offset(extent.sizeX() - 1, extent.sizeY() - 1, extent.sizeZ() - 1));
	}

	/** How many blocks inside {@code box} are not air, i.e. how much placing there would overwrite. */
	private static int nonAir(BuildBox box, ServerLevel level) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
			if (!level.getBlockState(pos).isAir()) {
				count++;
			}
		}
		return count;
	}
}
