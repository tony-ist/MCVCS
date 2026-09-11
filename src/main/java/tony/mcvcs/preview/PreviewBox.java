package tony.mcvcs.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

import io.netty.buffer.ByteBuf;

/**
 * The inclusive block box a preview covers, plus the fixed order its blocks are streamed in.
 * <p>
 * Both sides of the connection derive the block order from the box alone, so the block payloads only carry block
 * states, never positions. The order is x-major, then y, then z: {@code index = ((x * sizeY) + y) * sizeZ + z} with
 * all three relative to {@link #min()}.
 */
public record PreviewBox(BlockPos min, BlockPos max) {
	public static final StreamCodec<ByteBuf, PreviewBox> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, PreviewBox::min,
		BlockPos.STREAM_CODEC, PreviewBox::max,
		PreviewBox::new
	);

	public PreviewBox {
		if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
			throw new IllegalArgumentException("min " + min + " exceeds max " + max);
		}
	}

	public int sizeX() {
		return max.getX() - min.getX() + 1;
	}

	public int sizeY() {
		return max.getY() - min.getY() + 1;
	}

	public int sizeZ() {
		return max.getZ() - min.getZ() + 1;
	}

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public boolean contains(int x, int y, int z) {
		return x >= min.getX() && x <= max.getX()
			&& y >= min.getY() && y <= max.getY()
			&& z >= min.getZ() && z <= max.getZ();
	}

	public boolean contains(BlockPos pos) {
		return contains(pos.getX(), pos.getY(), pos.getZ());
	}

	/** Index of the block at world position {@code (x, y, z)}, which must be inside the box. */
	public int index(int x, int y, int z) {
		return ((x - min.getX()) * sizeY() + (y - min.getY())) * sizeZ() + (z - min.getZ());
	}

	/** World position of the block at {@code index}; the inverse of {@link #index(int, int, int)}. */
	public BlockPos pos(int index) {
		int z = index % sizeZ();
		int xy = index / sizeZ();
		int y = xy % sizeY();
		int x = xy / sizeY();
		return min.offset(x, y, z);
	}
}
