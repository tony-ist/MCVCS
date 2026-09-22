package tony.mcvcs.client.preview;

import java.util.Arrays;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import tony.mcvcs.MCVCS;
import tony.mcvcs.network.PlacePreviewBeginPayload;
import tony.mcvcs.network.PlacePreviewClearPayload;
import tony.mcvcs.network.PreviewBeginPayload;
import tony.mcvcs.network.PreviewBlocksPayload;
import tony.mcvcs.network.PreviewClearPayload;
import tony.mcvcs.build.BuildBox;
import org.jspecify.annotations.Nullable;

/**
 * Client side of the preview protocol. Holds what the server last sent and answers the chunk mesher's block lookups
 * inside its box, see {@link tony.mcvcs.client.mixin.RenderSectionRegionMixin}; also keeps the renderer from skipping
 * those boxes' sections as empty, see {@link tony.mcvcs.client.mixin.ClientChunkCacheMixin}.
 * <p>
 * Two things can be drawn at once, since they answer different questions: the version {@code /vcs preview} shows in
 * place of a placement's real blocks, and the copy a {@code /vcs place} is about to put down somewhere else in the
 * world. The copy wins where they overlap, being the thing the player is working with.
 * <p>
 * Packets and events are handled on the client thread; {@link #substitute} runs on the section compile threads and
 * only ever reads the immutable values published through {@link #active} and {@link #place}.
 */
public final class PreviewManager {
	/** The version preview currently drawn, or null to draw the real world in every placement. */
	private static volatile @Nullable ClientPreview active;
	/** The copy a {@code /vcs place} is showing, or null when none is being aligned. */
	private static volatile @Nullable PlacePreview place;
	/** Whichever of the two is still arriving slice by slice; null between previews. */
	private static @Nullable Pending pending;

	private PreviewManager() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PreviewBeginPayload.TYPE, (payload, context) ->
			pending = new Pending(payload.name(), payload.version(), payload.dimension(), payload.box(), false, false));
		ClientPlayNetworking.registerGlobalReceiver(PlacePreviewBeginPayload.TYPE, (payload, context) ->
			pending = new Pending(payload.label(), payload.version(), payload.dimension(), payload.box(), true, payload.force()));
		ClientPlayNetworking.registerGlobalReceiver(PreviewBlocksPayload.TYPE, (payload, context) -> blocks(payload, context.client()));
		ClientPlayNetworking.registerGlobalReceiver(PreviewClearPayload.TYPE, (payload, context) -> showVersion(null, context.client()));
		ClientPlayNetworking.registerGlobalReceiver(PlacePreviewClearPayload.TYPE, (payload, context) -> showPlace(null, context.client()));
		// A preview belongs to the world it was requested in, so leaving that world drops it.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> forget());
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> forget());
	}

	/** The version preview currently drawn, if any. */
	public static @Nullable ClientPreview active() {
		return active;
	}

	/** The copy a {@code /vcs place} is showing, if any. */
	public static @Nullable PlacePreview place() {
		return place;
	}

	/**
	 * Draws the copy a {@code /vcs place} is showing with its minimum corner at {@code min} instead, and has the
	 * renderer rebuild what it left and what it now covers. Does nothing when no copy is being shown.
	 */
	public static void movePlace(BlockPos min, Minecraft client) {
		PlacePreview showing = place;
		if (showing != null) {
			showPlace(showing.movedTo(min), client);
		}
	}

	/**
	 * The block to draw at {@code pos} in {@code level} instead of the real one, or null to draw the real block.
	 * Safe to call from any thread.
	 */
	public static @Nullable BlockState substitute(ClientLevel level, BlockPos pos) {
		ResourceKey<Level> dimension = level.dimension();
		PlacePreview showing = place;
		if (showing != null && showing.blocks().covers(dimension, pos)) {
			return showing.blocks().stateAt(pos);
		}
		ClientPreview preview = active;
		return preview != null && preview.covers(dimension, pos) ? preview.stateAt(pos) : null;
	}

	/**
	 * {@code emptySections} without the sections a preview draws blocks in, or {@code emptySections} itself when no
	 * preview touches any of them. See {@link tony.mcvcs.client.mixin.ClientChunkCacheMixin} for why. Called on the
	 * render thread every frame, so it only copies the set when it has to.
	 */
	public static LongOpenHashSet withoutPreviewed(ClientLevel level, LongOpenHashSet emptySections) {
		LongOpenHashSet result = emptySections;
		ClientPreview preview = active;
		if (preview != null && preview.dimension().equals(level.dimension())) {
			result = without(result, emptySections, preview.box());
		}
		PlacePreview showing = place;
		if (showing != null && showing.blocks().dimension().equals(level.dimension())) {
			result = without(result, emptySections, showing.box());
		}
		return result;
	}

	/** {@code result} without the sections {@code box} touches, copying {@code emptySections} the first time one is removed. */
	private static LongOpenHashSet without(LongOpenHashSet result, LongOpenHashSet emptySections, BuildBox box) {
		for (long section : sections(box)) {
			if (result.contains(section)) {
				if (result == emptySections) {
					result = new LongOpenHashSet(emptySections);
				}
				result.remove(section);
			}
		}
		return result;
	}

	private static void blocks(PreviewBlocksPayload payload, Minecraft client) {
		Pending target = pending;
		if (target == null) {
			MCVCS.LOGGER.warn("Ignoring preview blocks that arrived without a preview begin");
			return;
		}

		try {
			target.accept(payload);
		} catch (IndexOutOfBoundsException e) {
			MCVCS.LOGGER.error("Dropping preview '{}' v{}: malformed slice at offset {}", target.name, target.version, payload.offset(), e);
			pending = null;
			return;
		}

		if (payload.last()) {
			pending = null;
			if (target.place) {
				showPlace(new PlacePreview(target.build(), target.force), client);
			} else {
				showVersion(target.build(), client);
			}
		}
	}

	/** Drops all preview state without touching the renderer; for when the level it referred to is gone. */
	private static void forget() {
		pending = null;
		active = null;
		place = null;
	}

	/** Swaps the drawn version preview and has the renderer rebuild every section either the old or the new one touched. */
	private static void showVersion(@Nullable ClientPreview preview, Minecraft client) {
		ClientPreview previous = active;
		active = preview;
		if (preview == null) {
			dropPending(false);
		}

		rebuild(client, previous == null ? null : previous.box(), preview == null ? null : preview.box());
	}

	/** The same for the copy a {@code /vcs place} is showing, which moves far more often than it appears. */
	private static void showPlace(@Nullable PlacePreview preview, Minecraft client) {
		PlacePreview previous = place;
		place = preview;
		if (preview == null) {
			dropPending(true);
		}

		rebuild(client, previous == null ? null : previous.box(), preview == null ? null : preview.box());
	}

	/** Forgets a preview still arriving if its slices were meant for the one being cleared; the other one keeps its own. */
	private static void dropPending(boolean place) {
		if (pending != null && pending.place == place) {
			pending = null;
		}
	}

	/** Rebuilds whichever of the two boxes is there, the one left behind and the one now covered. */
	private static void rebuild(Minecraft client, @Nullable BuildBox previous, @Nullable BuildBox current) {
		if (client.level == null) {
			return;
		}
		if (previous != null) {
			rebuild(client, previous);
		}
		if (current != null) {
			rebuild(client, current);
		}
	}

	private static void rebuild(Minecraft client, BuildBox box) {
		BlockPos min = box.min();
		BlockPos max = box.max();
		// Expands by one block on each side, so neighbours re-evaluate face culling against the new contents.
		client.levelRenderer.setBlocksDirty(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
		// Sections that are all air in the real world are left out of the render graph entirely, so marking them dirty
		// alone does nothing; this is what vanilla calls when a block is placed in such a section, and it puts the
		// section back into the graph. Harmless for sections that were never left out.
		for (long section : sections(box)) {
			client.levelRenderer.onSectionBecomingNonEmpty(section);
		}
	}

	/** The {@link SectionPos#asLong packed positions} of every chunk section {@code box} touches. */
	private static LongList sections(BuildBox box) {
		int minX = SectionPos.blockToSectionCoord(box.min().getX());
		int minY = SectionPos.blockToSectionCoord(box.min().getY());
		int minZ = SectionPos.blockToSectionCoord(box.min().getZ());
		int maxX = SectionPos.blockToSectionCoord(box.max().getX());
		int maxY = SectionPos.blockToSectionCoord(box.max().getY());
		int maxZ = SectionPos.blockToSectionCoord(box.max().getZ());
		LongList sections = new LongArrayList((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					sections.add(SectionPos.asLong(x, y, z));
				}
			}
		}
		return sections;
	}

	/** A preview being assembled from its slices, whichever of the two it is meant for. Blocks not yet received are air. */
	private static final class Pending {
		private final String name;
		private final int version;
		private final ResourceKey<Level> dimension;
		private final BuildBox box;
		/** Whether the slices belong to the copy a {@code /vcs place} is showing rather than to a version preview. */
		private final boolean place;
		private final boolean force;
		private final BlockState[] blocks;

		Pending(String name, int version, ResourceKey<Level> dimension, BuildBox box, boolean place, boolean force) {
			this.name = name;
			this.version = version;
			this.dimension = dimension;
			this.box = box;
			this.place = place;
			this.force = force;
			this.blocks = new BlockState[Math.toIntExact(box.volume())];
			Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
		}

		void accept(PreviewBlocksPayload slice) {
			int[] indices = slice.indices();
			for (int i = 0; i < indices.length; i++) {
				blocks[slice.offset() + i] = slice.state(i);
			}
		}

		ClientPreview build() {
			return new ClientPreview(name, version, dimension, box, blocks);
		}
	}
}
