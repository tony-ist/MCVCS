package tony.mcvcs.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.diff.BlockChange;

/**
 * Server to client: one slice of the diff announced by the last {@link DiffBeginPayload}.
 * <p>
 * Each change is a block position, as its {@link BuildBox} index, plus the block on either side of the diff as
 * palette indices. Slices arrive in order and the diff is complete once as many changes as the begin payload
 * announced have arrived.
 *
 * @param palette distinct block states used by this slice
 * @param indices box index of each changed block
 * @param from    palette index of each block's old state
 * @param to      palette index of each block's new state
 */
public record DiffChangesPayload(List<BlockState> palette, int[] indices, int[] from, int[] to) implements CustomPacketPayload {
	public static final Type<DiffChangesPayload> TYPE = new Type<>(MCVCS.id("diff_changes"));
	public static final StreamCodec<FriendlyByteBuf, DiffChangesPayload> STREAM_CODEC = CustomPacketPayload.codec(DiffChangesPayload::write, DiffChangesPayload::read);

	public DiffChangesPayload {
		if (from.length != indices.length || to.length != indices.length) {
			throw new IllegalArgumentException("Slice has " + indices.length + " positions but " + from.length + " old and " + to.length + " new states");
		}
	}

	private static DiffChangesPayload read(FriendlyByteBuf buf) {
		List<BlockState> palette = buf.readList(BlockPalette.STATE_CODEC);
		int[] indices = buf.readVarIntArray();
		int[] from = buf.readVarIntArray();
		int[] to = buf.readVarIntArray();
		return new DiffChangesPayload(palette, indices, from, to);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeCollection(palette, BlockPalette.STATE_CODEC);
		buf.writeVarIntArray(indices);
		buf.writeVarIntArray(from);
		buf.writeVarIntArray(to);
	}

	/** Number of changes in this slice. */
	public int size() {
		return indices.length;
	}

	/**
	 * The changes of this slice as they were on the server, with positions resolved against {@code box}, the box of
	 * the begin payload.
	 *
	 * @throws IndexOutOfBoundsException if a box or palette index is out of range, i.e. the slice is malformed
	 */
	public List<BlockChange> changes(BuildBox box) {
		int volume = Math.toIntExact(box.volume());
		List<BlockChange> changes = new ArrayList<>(indices.length);
		for (int i = 0; i < indices.length; i++) {
			if (indices[i] < 0 || indices[i] >= volume) {
				throw new IndexOutOfBoundsException("Box index " + indices[i] + " is outside " + box);
			}
			changes.add(new BlockChange(box.pos(indices[i]), palette.get(from[i]), palette.get(to[i])));
		}
		return changes;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
