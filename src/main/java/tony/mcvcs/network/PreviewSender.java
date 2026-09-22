package tony.mcvcs.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

/**
 * Server side of the preview protocol: turns a schematic into {@link PreviewBeginPayload} and
 * {@link PreviewBlocksPayload}s. The same block payloads carry the copy a {@code /vcs place} is about to put down,
 * which {@link PlacePreviewBeginPayload} announces instead.
 */
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
		PayloadTypeRegistry.clientboundPlay().register(PlacePreviewBeginPayload.TYPE, PlacePreviewBeginPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlacePreviewClearPayload.TYPE, PlacePreviewClearPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PlacePreviewMovePayload.TYPE, PlacePreviewMovePayload.STREAM_CODEC);
	}

	/** Whether the player's client has this mod and can therefore show previews. */
	public static boolean canSend(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, PreviewBeginPayload.TYPE);
	}

	/** Whether it can show the copy a {@code /vcs place} is about to put down, which an older client may not. */
	public static boolean canSendPlace(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, PlacePreviewBeginPayload.TYPE);
	}

	/**
	 * Streams {@code clipboard}, version {@code version} of {@code placement}'s build, to {@code player} so the client
	 * shows it inside the placement's box. The version is laid where the placement holds it, with air around it if it
	 * is smaller than the box, see {@link BoxSnapshot#ofClipboard}.
	 *
	 * @throws IllegalArgumentException if the version does not fit the placement's box
	 */
	public static void send(ServerPlayer player, BuildPlacement placement, int version, Clipboard clipboard) {
		BuildBox box = placement.box();
		BoxSnapshot snapshot = BoxSnapshot.ofClipboard(box, clipboard, placement.boxOf(version));
		ServerPlayNetworking.send(player, new PreviewBeginPayload(placement.label(), version, placement.dimension(), box));
		stream(player, snapshot);
	}

	public static void clear(ServerPlayer player) {
		ServerPlayNetworking.send(player, PreviewClearPayload.INSTANCE);
	}

	/**
	 * Streams {@code clipboard}, the version {@code /vcs place} is about to put down, to {@code player} so the client
	 * draws it at {@code box} until the player moves it or confirms. Nothing in the world is touched; the box is the
	 * version's own size, so its blocks fill it exactly.
	 *
	 * @param label how the placement would be named, e.g. {@code tower/p2}
	 * @param force whether the command was given {@code -f}, which the client needs to colour the box
	 */
	public static void sendPlace(ServerPlayer player, String label, int version, ResourceKey<Level> dimension, BuildBox box, Clipboard clipboard, boolean force) {
		BoxSnapshot snapshot = BoxSnapshot.ofClipboard(box, clipboard, box);
		ServerPlayNetworking.send(player, new PlacePreviewBeginPayload(label, version, dimension, box, force));
		stream(player, snapshot);
	}

	/** Stops the client drawing the copy a {@code /vcs place} was about to put down. */
	public static void clearPlace(ServerPlayer player) {
		ServerPlayNetworking.send(player, PlacePreviewClearPayload.INSTANCE);
	}

	/** Sends {@code snapshot} as {@link PreviewBlocksPayload}s, whichever begin payload announced it. */
	private static void stream(ServerPlayer player, BoxSnapshot snapshot) {
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
}
