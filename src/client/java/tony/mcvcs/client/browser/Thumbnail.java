package tony.mcvcs.client.browser;

import java.util.Arrays;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.BuildBox;
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
	/** The blocks received so far, filling the key's extent; null before the first and once meshed. */
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

	/** How much of the preview has arrived, from 0 to 1. */
	public float progress() {
		long volume = key.extent().volume();
		return volume == 0 ? 1.0f : (float) received / volume;
	}

	/** The server has started sending the blocks, which will fill {@code extent}. */
	void begin(BuildBox extent) {
		if (!extent.equals(key.extent())) {
			fail("Build changed; refresh");
			return;
		}
		blocks = new BlockState[Math.toIntExact(extent.volume())];
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
			fail("Malformed preview");
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

	void fail(String reason) {
		blocks = null;
		failure = reason;
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
