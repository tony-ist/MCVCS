package tony.mcvcs.client.browser;

import java.util.Arrays;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.PreviewGrid;
import tony.mcvcs.network.ThumbnailBlocksPayload;

/**
 * The preview of one version of one build, from the moment the overlay asks for it until it can be drawn. A version
 * never changes once committed, so a finished preview is kept, see {@link BuildBrowser}, and drawn again every time
 * the overlay opens without asking the server twice. Only touched from the client thread.
 */
public final class Thumbnail {
	/** Where the preview is on its way to the screen. */
	public enum State {
		/** Asked for; the server has not started sending it yet. */
		REQUESTED,
		/** The server is sending its blocks. */
		DOWNLOADING,
		/** Every block has arrived and the mesh is being built. */
		MESHING,
		/** Ready to draw. */
		READY,
		/** The server could not send it, or it arrived broken; see {@link #failure()}. */
		FAILED
	}

	private final Key key;
	private State state = State.REQUESTED;
	private @Nullable String failure;
	/** Whether asking again could help, so a refresh should; not when the version itself is too much to preview. */
	private boolean retryable;
	/** The grid the blocks fill, the version's extent at scale 1 or a coarser grid of it, see {@link PreviewGrid}. */
	private @Nullable BuildBox grid;
	/** How many of the version's blocks each block of {@link #grid} stands for along each side. */
	private int scale = 1;
	/** The blocks received so far, filling {@link #grid}; null before the first and once meshed. */
	private BlockState @Nullable [] blocks;
	private long received;
	private @Nullable ThumbnailMesh mesh;

	/**
	 * Which preview this is. The extent is part of it because a deleted build's name can be taken again, and a new
	 * build's v1 is not the old one's; extents that differ tell the two apart in all but the unluckiest case.
	 */
	public record Key(String build, int version, BuildBox extent) {
	}

	Thumbnail(Key key) {
		this.key = key;
	}

	public Key key() {
		return key;
	}

	public State state() {
		return state;
	}

	/** Why the preview could not be had, while it is {@link State#FAILED}. */
	public @Nullable String failure() {
		return failure;
	}

	/** The mesh to draw, once {@link State#READY}. */
	public @Nullable ThumbnailMesh mesh() {
		return mesh;
	}

	/** Whether a refresh should ask for the preview again, while it is {@link State#FAILED}. */
	public boolean retryable() {
		return retryable;
	}

	/** The grid the blocks fill, once they have started arriving. */
	public @Nullable BuildBox grid() {
		return grid;
	}

	/** How many of the version's blocks each block of the preview stands for along each side; 1 for all but big builds. */
	public int scale() {
		return scale;
	}

	/** How much of the preview has arrived, from 0 to 1. */
	public float progress() {
		BuildBox filling = grid;
		long volume = filling == null ? 0 : filling.volume();
		return volume == 0 ? 0.0f : (float) received / volume;
	}

	/** The server has started sending the blocks of {@code extent}, sampled at {@code scale}, see {@link PreviewGrid}. */
	void begin(BuildBox extent, int scale) {
		if (!extent.equals(key.extent())) {
			fail("Build changed", true);
			return;
		}
		if (scale < 1) {
			fail("Malformed preview", true);
			return;
		}
		this.scale = scale;
		grid = PreviewGrid.grid(extent, scale);
		blocks = new BlockState[Math.toIntExact(grid.volume())];
		Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
		received = 0;
		state = State.DOWNLOADING;
	}

	/**
	 * Takes one slice of blocks.
	 *
	 * @return the finished blocks when this was the last slice, otherwise null
	 */
	BlockState @Nullable [] accept(ThumbnailBlocksPayload slice) {
		BlockState[] target = blocks;
		if (state != State.DOWNLOADING || target == null) {
			return null;
		}
		try {
			for (int i = 0; i < slice.indices().length; i++) {
				target[slice.offset() + i] = slice.state(i);
			}
		} catch (IndexOutOfBoundsException e) {
			fail("Malformed preview", true);
			return null;
		}
		received += slice.indices().length;
		if (!slice.last()) {
			return null;
		}
		blocks = null;
		state = State.MESHING;
		return target;
	}

	/** The mesh has been built and uploaded. */
	void ready(ThumbnailMesh mesh) {
		this.mesh = mesh;
		state = State.READY;
	}

	/**
	 * @param retryable whether asking again could help: true when the download went wrong, false when the version
	 *                  itself cannot be previewed, which a refresh would only find out again
	 */
	void fail(String reason, boolean retryable) {
		blocks = null;
		failure = reason;
		this.retryable = retryable;
		state = State.FAILED;
	}

	/** Frees the GPU memory of the mesh, if there is one. */
	void close() {
		ThumbnailMesh built = mesh;
		mesh = null;
		if (built != null) {
			built.close();
		}
	}
}
