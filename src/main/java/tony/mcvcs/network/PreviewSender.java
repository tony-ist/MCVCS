package tony.mcvcs.network;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.preview.PreviewBox;
import tony.mcvcs.project.Project;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/** Server side of the preview protocol: turns a schematic into {@link PreviewBeginPayload} and {@link PreviewBlocksPayload}s. */
public final class PreviewSender {
	/**
	 * Blocks per {@link PreviewBlocksPayload}. Palette indices and palette entries are var-ints of at most three
	 * bytes each, so a slice stays well under the 1 MiB custom payload limit even in the worst case.
	 */
	public static final int BLOCKS_PER_PACKET = 32768;

	private PreviewSender() {
	}

	/** Registers the payload types; must run on both sides, so it belongs in the main entrypoint. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(PreviewBeginPayload.TYPE, PreviewBeginPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreviewBlocksPayload.TYPE, PreviewBlocksPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreviewClearPayload.TYPE, PreviewClearPayload.STREAM_CODEC);
	}

	/** Whether the player's client has this mod and can therefore show previews. */
	public static boolean canSend(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, PreviewBeginPayload.TYPE);
	}

	/** The box {@code project}'s region covers in the world. */
	public static PreviewBox box(Project project) {
		Region region = project.region();
		FabricAdapter adapter = FabricAdapter.get();
		return new PreviewBox(adapter.toBlockPos(region.getMinimumPoint()), adapter.toBlockPos(region.getMaximumPoint()));
	}

	/**
	 * Streams {@code clipboard}, the schematic saved for {@code project}, to {@code player} so the client shows it
	 * inside the project's region. The clipboard's own coordinates are ignored: its blocks are laid over the project
	 * region corner to corner.
	 *
	 * @throws IllegalArgumentException if the clipboard is not the size of the project's region
	 */
	public static void send(ServerPlayer player, Project project, Clipboard clipboard) {
		PreviewBox box = box(project);
		BlockVector3 dimensions = clipboard.getDimensions();
		if (dimensions.x() != box.sizeX() || dimensions.y() != box.sizeY() || dimensions.z() != box.sizeZ()) {
			throw new IllegalArgumentException("Schematic is " + dimensions + " but the build's region is "
				+ BlockVector3.at(box.sizeX(), box.sizeY(), box.sizeZ()));
		}

		ServerPlayNetworking.send(player, new PreviewBeginPayload(project.name(), project.version(), player.level().dimension(), box));

		FabricAdapter adapter = FabricAdapter.get();
		BlockVector3 clipboardMin = clipboard.getMinimumPoint();
		BlockPos boxMin = box.min();
		int total = Math.toIntExact(box.volume());

		for (int offset = 0; offset < total; offset += BLOCKS_PER_PACKET) {
			int count = Math.min(BLOCKS_PER_PACKET, total - offset);
			List<BlockState> palette = new ArrayList<>();
			Object2IntMap<BlockState> paletteIndex = new Object2IntOpenHashMap<>();
			int[] indices = new int[count];

			for (int i = 0; i < count; i++) {
				BlockPos pos = box.pos(offset + i);
				BlockVector3 clipboardPos = clipboardMin.add(pos.getX() - boxMin.getX(), pos.getY() - boxMin.getY(), pos.getZ() - boxMin.getZ());
				BlockState state = adapter.toNativeBlockState(clipboard.getBlock(clipboardPos));

				int index = paletteIndex.getOrDefault(state, -1);
				if (index < 0) {
					index = palette.size();
					palette.add(state);
					paletteIndex.put(state, index);
				}
				indices[i] = index;
			}

			ServerPlayNetworking.send(player, new PreviewBlocksPayload(offset, palette, indices, offset + count == total));
		}
	}

	public static void clear(ServerPlayer player) {
		ServerPlayNetworking.send(player, PreviewClearPayload.INSTANCE);
	}
}
