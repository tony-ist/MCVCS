package tony.mcvcs.client.preview;

import java.util.Arrays;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import tony.mcvcs.MCVCS;
import tony.mcvcs.network.PreviewBeginPayload;
import tony.mcvcs.network.PreviewBlocksPayload;
import tony.mcvcs.network.PreviewClearPayload;
import tony.mcvcs.build.BuildBox;
import org.jspecify.annotations.Nullable;

/**
 * Client side of the preview protocol. Holds the preview the server last sent and answers the chunk mesher's block
 * lookups inside its box, see {@link tony.mcvcs.client.mixin.RenderSectionRegionMixin}; also keeps the renderer from
 * skipping the box's sections as empty, see {@link tony.mcvcs.client.mixin.ClientChunkCacheMixin}.
 * <p>
 * Packets and events are handled on the client thread; {@link #substitute} runs on the section compile threads and
 * only ever reads the immutable {@link ClientPreview} published through {@link #active}.
 */
public final class PreviewManager {
	/** The preview currently drawn, or null to draw the real world everywhere. */
	private static volatile @Nullable ClientPreview active;
	/** The preview whose slices are still arriving; null between previews. */
	private static @Nullable Pending pending;

	private PreviewManager() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PreviewBeginPayload.TYPE, (payload, context) -> begin(payload));
		ClientPlayNetworking.registerGlobalReceiver(PreviewBlocksPayload.TYPE, (payload, context) -> blocks(payload, context.client()));
		ClientPlayNetworking.registerGlobalReceiver(PreviewClearPayload.TYPE, (payload, context) -> clear(context.client()));
		// A preview belongs to the world it was requested in, so leaving that world drops it.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> forget());
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> forget());
	}

	/** The preview currently drawn, if any. */
	public static @Nullable ClientPreview active() {
		return active;
	}

	/**
	 * The block to draw at {@code pos} in {@code level} instead of the real one, or null to draw the real block.
	 * Safe to call from any thread.
	 */
	public static @Nullable BlockState substitute(ClientLevel level, BlockPos pos) {
		ClientPreview preview = active;
		return preview != null && preview.covers(level.dimension(), pos) ? preview.stateAt(pos) : null;
	}

	/**
	 * {@code emptySections} without the sections a preview draws blocks in, or {@code emptySections} itself when no
	 * preview touches any of them. See {@link tony.mcvcs.client.mixin.ClientChunkCacheMixin} for why. Called on the
	 * render thread every frame, so it only copies the set when it has to.
	 */
	public static LongOpenHashSet withoutPreviewed(ClientLevel level, LongOpenHashSet emptySections) {
		ClientPreview preview = active;
		if (preview == null || !preview.dimension().equals(level.dimension())) {
			return emptySections;
		}

		LongOpenHashSet result = emptySections;
		for (long section : sections(preview.box())) {
			if (result.contains(section)) {
				if (result == emptySections) {
					result = new LongOpenHashSet(emptySections);
				}
				result.remove(section);
			}
		}
		return result;
	}

	private static void begin(PreviewBeginPayload payload) {
		pending = new Pending(payload);
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
			MCVCS.LOGGER.error("Dropping preview '{}' v{}: malformed slice at offset {}", target.begin.name(), target.begin.version(), payload.offset(), e);
			pending = null;
			return;
		}

		if (payload.last()) {
			pending = null;
			show(target.build(), client);
		}
	}

	private static void clear(Minecraft client) {
		pending = null;
		show(null, client);
	}

	/** Drops all preview state without touching the renderer; for when the level it referred to is gone. */
	private static void forget() {
		pending = null;
		active = null;
	}

	/** Swaps the drawn preview and has the renderer rebuild every section either the old or the new one touched. */
	private static void show(@Nullable ClientPreview preview, Minecraft client) {
		ClientPreview previous = active;
		active = preview;

		if (client.level == null) {
			return;
		}
		if (previous != null) {
			rebuild(client, previous.box());
		}
		if (preview != null) {
			rebuild(client, preview.box());
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

	/** A preview being assembled from its slices. Blocks not yet received are air. */
	private static final class Pending {
		private final PreviewBeginPayload begin;
		private final BlockState[] blocks;

		Pending(PreviewBeginPayload begin) {
			this.begin = begin;
			this.blocks = new BlockState[Math.toIntExact(begin.box().volume())];
			Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
		}

		void accept(PreviewBlocksPayload slice) {
			int[] indices = slice.indices();
			for (int i = 0; i < indices.length; i++) {
				blocks[slice.offset() + i] = slice.state(i);
			}
		}

		ClientPreview build() {
			return new ClientPreview(begin.name(), begin.version(), begin.dimension(), begin.box(), blocks);
		}
	}
}
