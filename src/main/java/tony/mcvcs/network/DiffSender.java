package tony.mcvcs.network;

import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.diff.BlockChange;
import tony.mcvcs.diff.BuildDiff;

/** Server side of the diff protocol: turns a {@link BuildDiff} into a {@link DiffBeginPayload} and {@link DiffChangesPayload}s. */
public final class DiffSender {
	/**
	 * Changes per {@link DiffChangesPayload}. A change is three var-ints of at most three bytes each, so a slice
	 * stays well under the 1 MiB custom payload limit even with a palette as long as the slice.
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
	 * Streams {@code diff}, the world compared against {@code build} at its version, to {@code player} so the client
	 * highlights it inside the build's region.
	 *
	 * @throws IllegalArgumentException if the diff does not cover the build's box
	 */
	public static void send(ServerPlayer player, Build build, BuildDiff diff) {
		BuildBox box = build.box();
		if (!diff.box().equals(box)) {
			throw new IllegalArgumentException("Diff covers " + diff.box() + " but build '" + build.name() + "' covers " + box);
		}

		List<BlockChange> changes = diff.changes();
		int total = changes.size();
		ServerPlayNetworking.send(player, new DiffBeginPayload(build.name(), build.version(), build.dimension(), box, total));

		for (int offset = 0; offset < total; offset += CHANGES_PER_PACKET) {
			int count = Math.min(CHANGES_PER_PACKET, total - offset);
			BlockPalette palette = new BlockPalette();
			int[] indices = new int[count];
			int[] from = new int[count];
			int[] to = new int[count];
			for (int i = 0; i < count; i++) {
				BlockChange change = changes.get(offset + i);
				indices[i] = box.index(change.pos().getX(), change.pos().getY(), change.pos().getZ());
				from[i] = palette.indexOf(change.from());
				to[i] = palette.indexOf(change.to());
			}
			ServerPlayNetworking.send(player, new DiffChangesPayload(palette.states(), indices, from, to));
		}
	}

	public static void clear(ServerPlayer player) {
		ServerPlayNetworking.send(player, DiffClearPayload.INSTANCE);
	}
}
