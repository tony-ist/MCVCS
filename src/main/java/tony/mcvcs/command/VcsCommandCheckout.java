package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Objects;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockTypes;

/**
 * {@code /vcs checkout <version | latest> [-f]}: clears the selected build's box and puts that version, or its latest
 * one, back in it, exactly where it was committed from, without a single block update, as if {@code //perf off} were
 * on. Refuses while the box differs from the version it holds, since the changes would be lost: they have to be
 * committed first, unless {@code -f} is given, which overwrites them for good. The checkout is not put in the
 * player's WorldEdit history, so {@code //undo} never reverts it. The checked-out version becomes the one the box
 * holds, so checking out another version after it is allowed, and a commit from there saves the box as the next
 * version as usual. Any preview or diff highlighting the player had up is stopped, since both showed the box as it
 * was before.
 */
public final class VcsCommandCheckout {
	static final VcsHelp HELP = new VcsHelp("checkout", "/vcs checkout <version | latest> [" + VcsCommand.FORCE + "]",
		"put a version back into the world",
		"Empties the selected build's box and puts the provided version into it, without block updates. Refuses if the box has uncommitted changes: commit first, or add " + VcsCommand.FORCE + " to overwrite them. /vcs diff starts to compare versions against this checked out version.");

	private VcsCommandCheckout() {
	}

	/**
	 * Replaces whatever is inside the selected build's box with one of its versions: the whole box is set to air and
	 * the version's schematic put back at the world position it was committed from, so a version committed before
	 * the box grew lands where it was built and the rest of the grown box stays empty. The box itself does not change.
	 * <p>
	 * The blocks are set without any of the side effects {@code //perf off} turns off, see {@link #sideEffects}, so
	 * nothing in the version gets a block update while it is put back: redstone components, observers and falling
	 * blocks are left exactly as they were saved instead of reacting to their neighbours appearing one by one. The
	 * edit is kept out of the player's WorldEdit history: MCVCS and WorldEdit edits stay separate, so {@code //undo}
	 * only ever reverts the player's own WorldEdit edits, never a checkout, and a checkout never pushes one of those
	 * edits out of the history either.
	 * <p>
	 * Checking out throws away whatever is in the box, so it refuses while the box differs from the version it holds,
	 * see {@link Build#head}, by as much as one block or one block entity's data, the same way {@code /vcs diff} tells
	 * them apart; the player has to commit first, or pass {@link VcsCommand#FORCE} to have the changes overwritten,
	 * which nothing brings back. Once done, the checked-out version is the one the box holds.
	 *
	 * @param version the version to check out, or {@link VcsCommand#LATEST} for the selected build's latest one
	 * @param force   whether to check out over uncommitted changes instead of refusing
	 */
	static int run(CommandSourceStack source, int version, boolean force) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}

		Build latest = selected.get();
		if (version > latest.version()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(latest.name())).append(" only has versions 1 to " + latest.version()));
			return 0;
		}
		Build build = version == VcsCommand.LATEST ? latest : latest.atVersion(version);
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		Clipboard clipboard;
		// How many blocks the box differs from the version it holds by; forcing overwrites them, and the message says so.
		int overwritten;
		try {
			// Whatever was built since the box last held a version is about to be wiped, so it has to be in a version first.
			Build head = latest.atHead();
			BuildDiff uncommitted = BuildDiff.between(BoxSnapshot.ofClipboard(head.box(), BuildStorage.read(head)), BoxSnapshot.ofLevel(head.box(), level));
			if (!uncommitted.isEmpty() && !force) {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(head.name())).append(" is modified: " + uncommitted.size()
					+ (uncommitted.size() == 1 ? " block differs" : " blocks differ") + " from v" + head.version() + "; run ").append(ChatButtons.command("/vcs commit"))
					.append(" before checking out, ").append(ChatButtons.command("/vcs diff")).append(" to see the changes, or add " + VcsCommand.FORCE + " to discard them"));
				return 0;
			}
			overwritten = uncommitted.size();
			clipboard = BuildStorage.read(build);
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to check out build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}

		// The schematic keeps the region it was copied from; that is where it goes back, and it has to be inside the box
		// for the box to be the only thing the checkout touches.
		BuildBox covered = BuildBox.of(clipboard.getRegion());
		if (!build.box().contains(covered)) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " covers " + VcsMessages.size(covered) + " at " + covered.min().toShortString()
				+ ", which is not inside the build's box " + VcsMessages.size(build.box()) + " at " + build.box().min().toShortString()));
			return 0;
		}

		Player actor = FabricAdapter.get().fromNativePlayer(player);
		Region region = build.region(level);
		World world = region.getWorld();

		// Built here rather than by the player's WorldEdit session so their global mask, block bag and block change limit
		// cannot leave the checkout half done. The session is deliberately not given the edit to remember: a checkout is
		// not one of the player's WorldEdit edits, so //undo skips over it and only ever reverts their own edits.
		try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder().world(world).actor(actor).maxBlocks(-1).build()) {
			editSession.setSideEffectApplier(sideEffects());
			editSession.setBlocks(region, Objects.requireNonNull(BlockTypes.AIR).getDefaultState());
			// Same source and target coordinates: the copy is a paste back to where the schematic came from.
			ForwardExtentCopy paste = new ForwardExtentCopy(clipboard, clipboard.getRegion(), editSession, clipboard.getMinimumPoint());
			paste.setCopyingEntities(BuildSaver.COPY_ENTITIES);
			paste.setCopyingBiomes(BuildSaver.COPY_BIOMES);
			Operations.complete(paste);
		} catch (WorldEditException e) {
			MCVCS.LOGGER.error("Failed to check out build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to place schematic: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} checked out build '{}' v{} into its box in {}, overwriting {} uncommitted blocks", player.getGameProfile().name(), build.name(), build.version(), world == null ? "its dimension" : world.getName(), overwritten);
		// A preview shown before the checkout would hide the version that was just placed, and a diff highlighted before
		// it compared blocks that are gone now.
		if (PreviewSender.canSend(player)) {
			PreviewSender.clear(player);
		}
		if (DiffSender.canSend(player)) {
			DiffSender.clear(player);
		}

		// The box holds this version now; the next checkout, and /vcs diff without a version, measure changes against it.
		try {
			BuildStorage.update(latest.withHead(build.version()));
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to record v{} as checked out for build '{}' for {}", build.version(), build.name(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Checked out build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " but failed to record it: " + e.getMessage()));
			return 0;
		}

		source.sendSuccess(() -> {
			MutableComponent message = Component.literal("Checked out build ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " (" + covered.volume() + " blocks) into its box without block updates");
			if (overwritten > 0) {
				// Only a forced checkout gets here; the changes are gone, and not into WorldEdit's history either.
				message.append(", overwriting " + overwritten + " uncommitted " + (overwritten == 1 ? "block" : "blocks"));
			}
			if (build.version() == latest.version()) {
				return message;
			}
			return message.append("; the box now holds v" + build.version() + " rather than the latest v" + latest.version() + ", run ").append(ChatButtons.command("/vcs checkout latest")).append(" to go back to it");
		}, false);
		return 1;
	}

	/**
	 * The side effects of setting a block that the checkout keeps: what {@code //perf off} leaves on, which is only
	 * sending the change to clients and updating points of interest. Everything that command can turn off is off:
	 * lighting, neighbour notifications, block updates, validation against neighbours, entity AI and events, so
	 * placing a block has no consequence beyond the block being there.
	 */
	private static SideEffectSet sideEffects() {
		SideEffectSet sideEffects = SideEffectSet.defaults();
		for (SideEffect sideEffect : WorldEdit.getInstance().getPlatformManager().getSupportedSideEffects()) {
			if (sideEffect.isExposed()) {
				sideEffects = sideEffects.with(sideEffect, SideEffect.State.OFF);
			}
		}
		return sideEffects;
	}
}
