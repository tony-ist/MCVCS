package tony.mcvcs.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

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

	/**
	 * Streams {@code clipboard}, the schematic saved for {@code build}, to {@code player} so the client shows it
	 * inside the build's region. The clipboard's own coordinates are ignored: its blocks are laid over the build
	 * region corner to corner, see {@link BoxSnapshot#ofClipboard}.
	 *
	 * @throws IllegalArgumentException if the clipboard is not the size of the build's region
	 */
	public static void send(ServerPlayer player, Build build, Clipboard clipboard) {
		BoxSnapshot snapshot = BoxSnapshot.ofClipboard(build.box(), clipboard);
		ServerPlayNetworking.send(player, new PreviewBeginPayload(build.name(), build.version(), build.dimension(), build.box()));

		int total = snapshot.size();
		for (int offset = 0; offset < total; offset += BLOCKS_PER_PACKET) {
			int count = Math.min(BLOCKS_PER_PACKET, total - offset);
			BlockPalette palette = new BlockPalette();
			int[] indices = new int[count];
			for (int i = 0; i < count; i++) {
				indices[i] = palette.indexOf(snapshot.state(offset + i));
			}
			ServerPlayNetworking.send(player, new PreviewBlocksPayload(offset, palette.states(), indices, offset + count == total));
		}
	}

	public static void clear(ServerPlayer player) {
		ServerPlayNetworking.send(player, PreviewClearPayload.INSTANCE);
	}
}
