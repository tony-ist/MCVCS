package tony.mcvcs.build;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.regions.Region;
import io.netty.buffer.ByteBuf;

/**
 * The inclusive block box a build's region covers, in a form the client understands, plus the fixed order its
 * blocks are streamed in for previews.
 * <p>
 * Both sides of the connection derive the block order from the box alone, so the block payloads only carry block
 * states, never positions. The order is x-major, then y, then z: {@code index = ((x * sizeY) + y) * sizeZ + z} with
 * all three relative to {@link #min()}.
 */
public record BuildBox(BlockPos min, BlockPos max) {
	/** Saved as {@code min} and {@code max} fields; a box whose corners are the wrong way round is a data error. */
	public static final Codec<BuildBox> CODEC = Codec.pair(
		BlockPos.CODEC.fieldOf("min").codec(),
		BlockPos.CODEC.fieldOf("max").codec()
	).comapFlatMap(corners -> {
		try {
			return DataResult.success(new BuildBox(corners.getFirst(), corners.getSecond()));
		} catch (IllegalArgumentException e) {
			return DataResult.error(e::getMessage);
		}
	}, box -> Pair.of(box.min(), box.max()));
	public static final StreamCodec<ByteBuf, BuildBox> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, BuildBox::min,
		BlockPos.STREAM_CODEC, BuildBox::max,
		BuildBox::new
	);

	public BuildBox {
		if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
			throw new IllegalArgumentException("min " + min + " exceeds max " + max);
		}
	}

	/** The bounding box of a WorldEdit region. */
	public static BuildBox of(Region region) {
		FabricAdapter adapter = FabricAdapter.get();
		return new BuildBox(adapter.toBlockPos(region.getMinimumPoint()), adapter.toBlockPos(region.getMaximumPoint()));
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

	/** Whether every block of {@code other} is inside this box. */
	public boolean contains(BuildBox other) {
		return contains(other.min) && contains(other.max);
	}

	/** Whether the two boxes share at least one block. */
	public boolean intersects(BuildBox other) {
		return min.getX() <= other.max.getX() && max.getX() >= other.min.getX()
			&& min.getY() <= other.max.getY() && max.getY() >= other.min.getY()
			&& min.getZ() <= other.max.getZ() && max.getZ() >= other.min.getZ();
	}

	/** The box with {@code blocks} more layers on every side. */
	public BuildBox grow(int blocks) {
		return new BuildBox(min.offset(-blocks, -blocks, -blocks), max.offset(blocks, blocks, blocks));
	}

	/** The smallest box containing both this box and {@code other}. */
	public BuildBox union(BuildBox other) {
		return new BuildBox(
			new BlockPos(Math.min(min.getX(), other.min.getX()), Math.min(min.getY(), other.min.getY()), Math.min(min.getZ(), other.min.getZ())),
			new BlockPos(Math.max(max.getX(), other.max.getX()), Math.max(max.getY(), other.max.getY()), Math.max(max.getZ(), other.max.getZ()))
		);
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
