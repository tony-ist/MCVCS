package tony.mcvcs.command;

import java.util.List;
import java.util.Objects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.build.BuildBox;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;

/**
 * Puts a version of a build into the world, the way {@code /vcs checkout} and {@code /vcs place} both do: the boxes
 * given are emptied and the version's schematic laid over the box it belongs in.
 * <p>
 * The blocks are set without any of the side effects {@code //perf off} turns off, see {@link #sideEffects}, so
 * nothing in the version gets a block update while it is put down: redstone components, observers and falling
 * blocks are left exactly as they were saved instead of reacting to their neighbours appearing one by one. The edit
 * is kept out of the player's WorldEdit history: MCVCS and WorldEdit edits stay separate, so {@code //undo} only
 * ever reverts the player's own WorldEdit edits, never a checkout or a placement, and neither pushes one of those
 * edits out of the history.
 */
final class BuildPlacer {
	private BuildPlacer() {
	}

	/**
	 * Empties every box in {@code clear} and puts {@code clipboard} into {@code covered}, which must be the world
	 * box the version belongs in at its placement and the size of the clipboard's own region. A schematic carries
	 * the coordinates of the placement it was committed from, which mean nothing at another one, so only its shape
	 * is used and {@code covered} says where it goes.
	 */
	static void place(ServerPlayer player, ServerLevel level, List<BuildBox> clear, Clipboard clipboard, BuildBox covered) throws WorldEditException {
		FabricAdapter adapter = FabricAdapter.get();
		Player actor = adapter.fromNativePlayer(player);

		// Built here rather than by the player's WorldEdit session so their global mask, block bag and block change
		// limit cannot leave the edit half done. The session is deliberately not given the edit to remember.
		try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder().world(adapter.fromNativeWorld(level)).actor(actor).maxBlocks(-1).build()) {
			editSession.setSideEffectApplier(sideEffects());
			for (BuildBox box : clear) {
				editSession.setBlocks(box.region(level), Objects.requireNonNull(BlockTypes.AIR).getDefaultState());
			}
			BlockVector3 to = BlockVector3.at(covered.min().getX(), covered.min().getY(), covered.min().getZ());
			ForwardExtentCopy paste = new ForwardExtentCopy(clipboard, clipboard.getRegion(), editSession, to);
			paste.setCopyingEntities(BuildSaver.COPY_ENTITIES);
			paste.setCopyingBiomes(BuildSaver.COPY_BIOMES);
			Operations.complete(paste);
		}
	}

	/**
	 * The side effects of setting a block that a checkout or a placement keeps: what {@code //perf off} leaves on,
	 * which is only sending the change to clients and updating points of interest. Everything that command can turn
	 * off is off: lighting, neighbour notifications, block updates, validation against neighbours, entity AI and
	 * events, so placing a block has no consequence beyond the block being there.
	 */
	static SideEffectSet sideEffects() {
		SideEffectSet sideEffects = SideEffectSet.defaults();
		for (SideEffect sideEffect : WorldEdit.getInstance().getPlatformManager().getSupportedSideEffects()) {
			if (sideEffect.isExposed()) {
				sideEffects = sideEffects.with(sideEffect, SideEffect.State.OFF);
			}
		}
		return sideEffects;
	}
}
