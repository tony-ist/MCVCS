package tony.mcvcs.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import tony.mcvcs.MCVCS;
import tony.mcvcs.diff.ChangeKind;

/**
 * Server to client, after a {@link CommitPreviewPayload}: some of the blocks of the preview grid that differ from the
 * version the placement holds, see {@link tony.mcvcs.build.PreviewGrid}. A block of a coarse grid stands for a cube of
 * the placement's blocks; it is marked if any of them changed, as {@link ChangeKind#CHANGED} if they changed in
 * different ways.
 *
 * @param build the build's name
 * @param cells grid index of each marked block, in {@link tony.mcvcs.build.BuildBox} order
 * @param kinds the {@link ChangeKind} ordinal of each
 */
public record CommitChangesPayload(String build, int[] cells, byte[] kinds) implements CustomPacketPayload {
	/** Marked blocks per payload: at most five bytes each, well under the custom payload limit. */
	public static final int PER_PACKET = 65536;
	public static final Type<CommitChangesPayload> TYPE = new Type<>(MCVCS.id("commit_changes"));
	public static final StreamCodec<FriendlyByteBuf, CommitChangesPayload> STREAM_CODEC = CustomPacketPayload.codec(CommitChangesPayload::write, CommitChangesPayload::read);

	private static CommitChangesPayload read(FriendlyByteBuf buf) {
		return new CommitChangesPayload(buf.readUtf(), buf.readVarIntArray(), buf.readByteArray());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(build);
		buf.writeVarIntArray(cells);
		buf.writeByteArray(kinds);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
