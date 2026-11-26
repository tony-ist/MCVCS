package tony.mcvcs.network;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.netty.buffer.ByteBuf;
import tony.mcvcs.MCVCS;

/**
 * Server to client, in answer to a {@link CommitPreviewRequestPayload}: what committing the selected placement would
 * change. Followed by {@link CommitChangesPayload}s marking the changed blocks, then by the blocks in the placement's
 * box as a {@link ThumbnailBeginPayload} and {@link ThumbnailBlocksPayload}s for {@link #VERSION}, or by a
 * {@link ThumbnailFailedPayload} for it.
 *
 * @param build     the build's name; empty if no placement is selected or the player may not see it
 * @param placement the selected placement's label, e.g. {@code tower/main}
 * @param added     how many blocks were added since the version the placement holds, or {@link #UNKNOWN}
 * @param removed   how many were removed, or {@link #UNKNOWN}
 * @param changed   how many were swapped for another block or had their data changed, or {@link #UNKNOWN}
 */
public record CommitPreviewPayload(String build, String placement, int added, int removed, int changed) implements CustomPacketPayload {
	/** What the preview payloads carry as the version for the blocks not committed yet; versions start at 1. */
	public static final int VERSION = 0;
	/** A count that could not be worked out, as when the version the placement holds cannot be read. */
	public static final int UNKNOWN = -1;
	public static final Type<CommitPreviewPayload> TYPE = new Type<>(MCVCS.id("commit_preview"));
	public static final StreamCodec<ByteBuf, CommitPreviewPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, CommitPreviewPayload::build,
		ByteBufCodecs.STRING_UTF8, CommitPreviewPayload::placement,
		ByteBufCodecs.VAR_INT, CommitPreviewPayload::added,
		ByteBufCodecs.VAR_INT, CommitPreviewPayload::removed,
		ByteBufCodecs.VAR_INT, CommitPreviewPayload::changed,
		CommitPreviewPayload::new
	);

	/** Whether the counts are known. */
	public boolean known() {
		return added != UNKNOWN;
	}

	/** How many blocks differ in all, or {@link #UNKNOWN}. */
	public int total() {
		return known() ? added + removed + changed : UNKNOWN;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
