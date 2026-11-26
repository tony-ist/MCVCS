package tony.mcvcs.client.browser;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.network.CommitChangesPayload;
import tony.mcvcs.network.CommitPreviewPayload;

/**
 * What committing the selected placement would save, as the commit window shows it: how many blocks changed, see
 * {@link #summary()}, and a preview of the blocks in the placement's box with the changed ones marked, see
 * {@link #thumbnail()}. Asked for each time the window opens and dropped when it closes, see {@link BuildBrowser}, since
 * the blocks can change at any moment. Only touched from the client thread.
 */
public final class CommitPreview {
	private final ClientPlacement placement;
	private final Thumbnail thumbnail;
	private @Nullable CommitPreviewPayload summary;
	private final IntArrayList cells = new IntArrayList();
	private final ByteArrayList kinds = new ByteArrayList();

	CommitPreview(ClientPlacement placement) {
		this.placement = placement;
		this.thumbnail = new Thumbnail(new Thumbnail.Key(placement.build(), CommitPreviewPayload.VERSION, placement.box()));
	}

	public ClientPlacement placement() {
		return placement;
	}

	/** The preview of the blocks in the placement's box. */
	public Thumbnail thumbnail() {
		return thumbnail;
	}

	/** How many blocks were added, removed and changed, or null until the server says. */
	public @Nullable CommitPreviewPayload summary() {
		return summary;
	}

	void summarise(CommitPreviewPayload payload) {
		summary = payload;
	}

	void mark(CommitChangesPayload payload) {
		if (payload.cells().length != payload.kinds().length) {
			return;
		}
		cells.addElements(cells.size(), payload.cells());
		kinds.addElements(kinds.size(), payload.kinds());
	}

	/** One byte per block of {@code grid}: 0 for unchanged, otherwise one more than its kind of change. */
	byte[] marks(BuildBox grid) {
		byte[] marks = new byte[Math.toIntExact(grid.volume())];
		for (int i = 0; i < cells.size(); i++) {
			int cell = cells.getInt(i);
			if (cell >= 0 && cell < marks.length) {
				marks[cell] = (byte) (kinds.getByte(i) + 1);
			}
		}
		return marks;
	}
}
