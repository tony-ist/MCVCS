package tony.mcvcs.build;

import java.util.Arrays;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.sk89q.worldedit.coremc.internal.NBTConverter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.jspecify.annotations.Nullable;

/**
 * Every block inside a build's box at one moment, in {@link BuildBox} order: the world as it is now, or a version as
 * it was saved. Two snapshots of the same box can be compared block for block, see {@link tony.mcvcs.diff.BuildDiff},
 * or streamed to a client, see {@link tony.mcvcs.network.PreviewSender}.
 * <p>
 * Besides its state, a block with a block entity contributes that entity's saved data: the contents of a container,
 * the text of a sign, the timers of a furnace. The data is kept as the block entity saves it minus its id and
 * position, which the snapshot already knows, so what the world reports and what a schematic holds compare equal
 * when nothing changed. Neither array nor map may be modified after construction.
 *
 * @param box           the box the blocks fill
 * @param blocks        one state per block of the box, indexed by {@link BuildBox#index}
 * @param blockEntities the data of every block entity in the box that has any, by the same index
 */
public record BoxSnapshot(BuildBox box, BlockState[] blocks, Int2ObjectMap<CompoundTag> blockEntities) {
	/** Keys of a saved block entity that say what and where it is rather than what it holds. */
	private static final Set<String> METADATA_KEYS = Set.of("id", "x", "y", "z");

	public BoxSnapshot {
		if (blocks.length != box.volume()) {
			throw new IllegalArgumentException("Expected " + box.volume() + " blocks for " + box + " but got " + blocks.length);
		}
		blockEntities = Int2ObjectMaps.unmodifiable(blockEntities);
	}

	/** The blocks currently inside {@code box} in {@code level}. On the server this loads any chunk the box touches. */
	public static BoxSnapshot ofLevel(BuildBox box, Level level) {
		BlockState[] blocks = new BlockState[Math.toIntExact(box.volume())];
		Int2ObjectMap<CompoundTag> blockEntities = new Int2ObjectOpenHashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
			int index = box.index(pos.getX(), pos.getY(), pos.getZ());
			blocks[index] = level.getBlockState(pos);
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null) {
				put(blockEntities, index, blockEntity.saveWithoutMetadata(level.registryAccess()));
			}
		}
		return new BoxSnapshot(box, blocks, blockEntities);
	}

	/**
	 * The blocks of {@code clipboard}, a schematic saved for a build whose box is {@code box}, laid inside the box at
	 * the clipboard's own world coordinates. A schematic keeps the region it was copied from, so a version saved before
	 * the box grew, see {@code /vcs expand}, lands exactly where it was built and the rest of the box is air: every
	 * version of a build is looked at through the build's current box, whatever its own size.
	 *
	 * @throws IllegalArgumentException if the clipboard's region reaches outside the box
	 */
	public static BoxSnapshot ofClipboard(BuildBox box, Clipboard clipboard) {
		BuildBox covered = BuildBox.of(clipboard.getRegion());
		if (!box.contains(covered)) {
			throw new IllegalArgumentException("Schematic covers " + covered + ", which is not inside the build's box " + box);
		}

		FabricAdapter adapter = FabricAdapter.get();
		BlockState[] blocks = new BlockState[Math.toIntExact(box.volume())];
		Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
		Int2ObjectMap<CompoundTag> blockEntities = new Int2ObjectOpenHashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(covered.min(), covered.max())) {
			int index = box.index(pos.getX(), pos.getY(), pos.getZ());
			BlockVector3 clipboardPos = BlockVector3.at(pos.getX(), pos.getY(), pos.getZ());
			blocks[index] = adapter.toNativeBlockState(clipboard.getBlock(clipboardPos));
			// The reader puts the block entity's id and position back into its data; normalising strips them again.
			LinCompoundTag data = clipboard.getFullBlock(clipboardPos).getNbt();
			if (data != null) {
				put(blockEntities, index, NBTConverter.toNative(data));
			}
		}
		return new BoxSnapshot(box, blocks, blockEntities);
	}

	/** Records {@code data} for the block at {@code index} unless nothing is left of it once normalised. */
	private static void put(Int2ObjectMap<CompoundTag> blockEntities, int index, CompoundTag data) {
		CompoundTag normalised = normalise(data);
		if (normalised != null) {
			blockEntities.put(index, normalised);
		}
	}

	/**
	 * {@code data} without the keys that identify or locate the block entity, or null if that leaves nothing, so
	 * a block entity with no data of its own is the same as none. {@code data} itself is left alone.
	 */
	public static @Nullable CompoundTag normalise(CompoundTag data) {
		CompoundTag normalised = data.copy();
		for (String key : METADATA_KEYS) {
			normalised.remove(key);
		}
		return normalised.isEmpty() ? null : normalised;
	}

	/** The block at box index {@code index}. */
	public BlockState state(int index) {
		return blocks[index];
	}

	/** The block at world position {@code pos}, which must be inside the box. */
	public BlockState stateAt(BlockPos pos) {
		return blocks[box.index(pos.getX(), pos.getY(), pos.getZ())];
	}

	/** The block entity data of the block at box index {@code index}, or null if it has none. */
	public @Nullable CompoundTag data(int index) {
		return blockEntities.get(index);
	}

	/** The block entity data of the block at world position {@code pos}, which must be inside the box, or null if it has none. */
	public @Nullable CompoundTag dataAt(BlockPos pos) {
		return data(box.index(pos.getX(), pos.getY(), pos.getZ()));
	}

	/** Number of blocks in the snapshot, the box's volume. */
	public int size() {
		return blocks.length;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof BoxSnapshot snapshot && box.equals(snapshot.box) && Arrays.equals(blocks, snapshot.blocks) && blockEntities.equals(snapshot.blockEntities);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * box.hashCode() + Arrays.hashCode(blocks)) + blockEntities.hashCode();
	}

	@Override
	public String toString() {
		return "BoxSnapshot[box=" + box + ", blocks=" + blocks.length + ", blockEntities=" + blockEntities.size() + "]";
	}
}
