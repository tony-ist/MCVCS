package tony.mcvcs.network;

import java.util.BitSet;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.diff.BlockChange;
import tony.mcvcs.diff.BuildDiff;

/** Server side of the diff protocol: turns a {@link BuildDiff} into a {@link DiffBeginPayload} and {@link DiffChangesPayload}s. */
public final class DiffSender {
	/**
	 * Changes per {@link DiffChangesPayload}. A change is three var-ints of at most three bytes each plus a bit, so a
	 * slice stays well under the 1 MiB custom payload limit even with a palette as long as the slice.
	 */
	public static final int CHANGES_PER_PACKET = 32768;

	private DiffSender() {
	}

	/** Registers the payload types; must run on both sides, so it belongs in the main entrypoint. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(DiffBeginPayload.TYPE, DiffBeginPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DiffChangesPayload.TYPE, DiffChangesPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DiffClearPayload.TYPE, DiffClearPayload.STREAM_CODEC);
	}

	/** Whether the player's client has this mod and can therefore highlight diffs. */
	public static boolean canSend(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, DiffBeginPayload.TYPE);
	}

	/**
	 * Streams {@code diff}, what is inside {@code placement} compared against version {@code version} of its build,
	 * to {@code player} so the client highlights it inside the placement's box.
	 *
	 * @throws IllegalArgumentException if the diff does not cover the placement's box
	 */
	public static void send(ServerPlayer player, BuildPlacement placement, int version, BuildDiff diff) {
		BuildBox box = placement.box();
		if (!diff.box().equals(box)) {
			throw new IllegalArgumentException("Diff covers " + diff.box() + " but '" + placement.label() + "' covers " + box);
		}

		List<BlockChange> changes = diff.changes();
		int total = changes.size();
		ServerPlayNetworking.send(player, new DiffBeginPayload(placement.label(), version, placement.dimension(), box, total));

		for (int offset = 0; offset < total; offset += CHANGES_PER_PACKET) {
			int count = Math.min(CHANGES_PER_PACKET, total - offset);
			BlockPalette palette = new BlockPalette();
			int[] indices = new int[count];
			int[] from = new int[count];
			int[] to = new int[count];
			BitSet dataChanged = new BitSet(count);
			for (int i = 0; i < count; i++) {
				BlockChange change = changes.get(offset + i);
				indices[i] = box.index(change.pos().getX(), change.pos().getY(), change.pos().getZ());
				from[i] = palette.indexOf(change.from());
				to[i] = palette.indexOf(change.to());
				dataChanged.set(i, change.dataChanged());
			}
			ServerPlayNetworking.send(player, new DiffChangesPayload(palette.states(), indices, from, to, dataChanged));
		}
	}

	public static void clear(ServerPlayer player) {
		ServerPlayNetworking.send(player, DiffClearPayload.INSTANCE);
	}
}
