package tony.mcvcs.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.MCVCS;

/**
 * Reads and writes {@link Schematic}s as Sponge schematics, version 3: the {@code .schem} files WorldEdit and other
 * tools load. Only blocks and block entities are kept; entities and biomes are not.
 * <p>
 * The file is gzipped NBT, a root holding one {@code Schematic} compound:
 * <ul>
 * <li>{@code Width}, {@code Height}, {@code Length}: the size along x, y and z, as unsigned shorts.</li>
 * <li>{@code Offset}: the box's minimum corner minus the origin.</li>
 * <li>{@code Metadata.WorldEdit.Origin}: the origin in world coordinates, which is where WorldEdit keeps it.</li>
 * <li>{@code Blocks.Palette}: block state string to palette index; {@code Blocks.Data}: one var-int palette index per
 * block, at {@code x + z * Width + y * Width * Length}; {@code Blocks.BlockEntities}: {@code Pos} relative to the
 * minimum corner, {@code Id} and {@code Data}.</li>
 * <li>{@code DataVersion}: the game version the file was written by. A file from an older version has its block states
 * and block entities upgraded by the game's data fixer as it is read.</li>
 * </ul>
 * A block the game does not know, such as one from a mod that is gone, reads as air.
 */
public final class SpongeSchematic {
	/** File extension of a schematic. */
	public static final String EXTENSION = "schem";
	private static final int FORMAT_VERSION = 3;

	private SpongeSchematic() {
	}

	public static void write(Schematic schematic, OutputStream out) throws IOException {
		BoxSnapshot blocks = schematic.blocks();
		BuildBox box = blocks.box();
		int width = box.sizeX();
		int height = box.sizeY();
		int length = box.sizeZ();
		if (width > 0xFFFF || height > 0xFFFF || length > 0xFFFF) {
			throw new IOException("A schematic can be at most 65535 blocks along each side, not " + width + "x" + height + "x" + length);
		}

		Object2IntMap<BlockState> palette = new Object2IntLinkedOpenHashMap<>();
		palette.defaultReturnValue(-1);
		ByteArrayOutputStream data = new ByteArrayOutputStream(Math.toIntExact(box.volume()));
		ListTag blockEntities = new ListTag();
		BlockPos min = box.min();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < length; z++) {
				for (int x = 0; x < width; x++) {
					int index = box.index(min.getX() + x, min.getY() + y, min.getZ() + z);
					BlockState state = blocks.state(index);
					int id = palette.getInt(state);
					if (id < 0) {
						id = palette.size();
						palette.put(state, id);
					}
					writeVarInt(data, id);

					CompoundTag blockEntity = blocks.data(index);
					Identifier type = blockEntity == null ? null : blockEntityType(state);
					if (type != null) {
						CompoundTag entry = new CompoundTag();
						entry.putIntArray("Pos", new int[]{x, y, z});
						entry.putString("Id", type.toString());
						entry.put("Data", blockEntity.copy());
						blockEntities.add(entry);
					}
				}
			}
		}

		CompoundTag paletteTag = new CompoundTag();
		palette.forEach((state, id) -> paletteTag.putInt(BlockStateParser.serialize(state), id));
		CompoundTag blocksTag = new CompoundTag();
		blocksTag.put("Palette", paletteTag);
		blocksTag.putByteArray("Data", data.toByteArray());
		blocksTag.put("BlockEntities", blockEntities);

		BlockPos origin = schematic.origin();
		CompoundTag worldEdit = new CompoundTag();
		worldEdit.putIntArray("Origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
		CompoundTag metadata = new CompoundTag();
		metadata.putLong("Date", System.currentTimeMillis());
		metadata.put("WorldEdit", worldEdit);

		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", FORMAT_VERSION);
		tag.putInt("DataVersion", currentDataVersion());
		tag.put("Metadata", metadata);
		tag.putShort("Width", (short) width);
		tag.putShort("Height", (short) height);
		tag.putShort("Length", (short) length);
		tag.putIntArray("Offset", new int[]{min.getX() - origin.getX(), min.getY() - origin.getY(), min.getZ() - origin.getZ()});
		tag.put("Blocks", blocksTag);

		CompoundTag root = new CompoundTag();
		root.put("Schematic", tag);
		NbtIo.writeCompressed(root, out);
	}

	public static Schematic read(InputStream in) throws IOException {
		CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()).getCompound("Schematic")
			.orElseThrow(() -> new IOException("Not a Sponge schematic: no Schematic compound"));
		int formatVersion = tag.getIntOr("Version", -1);
		if (formatVersion != FORMAT_VERSION) {
			throw new IOException("Unsupported Sponge schematic version " + formatVersion + ", only " + FORMAT_VERSION + " can be read");
		}
		int dataVersion = tag.getIntOr("DataVersion", -1);
		if (dataVersion < 0) {
			throw new IOException("Schematic has no DataVersion");
		}
		int width = tag.getShortOr("Width", (short) 0) & 0xFFFF;
		int height = tag.getShortOr("Height", (short) 0) & 0xFFFF;
		int length = tag.getShortOr("Length", (short) 0) & 0xFFFF;
		if (width == 0 || height == 0 || length == 0) {
			throw new IOException("Schematic is " + width + "x" + height + "x" + length);
		}
		int[] offset = vector(tag.getIntArray("Offset").orElse(new int[3]), "Offset");
		int[] originTag = vector(tag.getCompoundOrEmpty("Metadata").getCompoundOrEmpty("WorldEdit").getIntArray("Origin").orElse(new int[3]), "Origin");
		BlockPos origin = new BlockPos(originTag[0], originTag[1], originTag[2]);
		BlockPos min = origin.offset(offset[0], offset[1], offset[2]);
		BuildBox box = new BuildBox(min, min.offset(width - 1, height - 1, length - 1));

		CompoundTag blocksTag = tag.getCompoundOrEmpty("Blocks");
		boolean upgrade = dataVersion < currentDataVersion();
		CompoundTag paletteTag = blocksTag.getCompoundOrEmpty("Palette");
		Map<Integer, BlockState> palette = new HashMap<>();
		for (String key : paletteTag.keySet()) {
			palette.put(paletteTag.getIntOr(key, -1), blockState(key, dataVersion, upgrade));
		}

		byte[] data = blocksTag.getByteArray("Data").orElseThrow(() -> new IOException("Schematic has no block data"));
		BlockState[] states = new BlockState[Math.toIntExact(box.volume())];
		int[] cursor = {0};
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < length; z++) {
				for (int x = 0; x < width; x++) {
					int id = readVarInt(data, cursor);
					BlockState state = palette.get(id);
					if (state == null) {
						throw new IOException("Block at " + x + " " + y + " " + z + " has palette index " + id + ", which the palette does not have");
					}
					states[box.index(min.getX() + x, min.getY() + y, min.getZ() + z)] = state;
				}
			}
		}

		Int2ObjectMap<CompoundTag> blockEntities = new Int2ObjectOpenHashMap<>();
		for (Tag entryTag : blocksTag.getListOrEmpty("BlockEntities")) {
			if (!(entryTag instanceof CompoundTag entry)) {
				continue;
			}
			int[] pos = entry.getIntArray("Pos").orElse(null);
			if (pos == null || pos.length != 3 || !box.contains(min.getX() + pos[0], min.getY() + pos[1], min.getZ() + pos[2])) {
				MCVCS.LOGGER.warn("Skipping a block entity outside the schematic at {}", pos == null ? null : Arrays.toString(pos));
				continue;
			}
			CompoundTag blockEntity = entry.getCompoundOrEmpty("Data").copy();
			if (upgrade) {
				blockEntity = upgradeBlockEntity(blockEntity, entry.getStringOr("Id", ""), dataVersion);
			}
			CompoundTag normalised = BoxSnapshot.normalise(blockEntity);
			if (normalised != null) {
				blockEntities.put(box.index(min.getX() + pos[0], min.getY() + pos[1], min.getZ() + pos[2]), normalised);
			}
		}
		return new Schematic(origin, new BoxSnapshot(box, states, blockEntities));
	}

	/** {@code value} if it has three components; a file that says otherwise is malformed. */
	private static int[] vector(int[] value, String name) throws IOException {
		if (value.length != 3) {
			throw new IOException("Schematic " + name + " has " + value.length + " components instead of 3");
		}
		return value;
	}

	/**
	 * The block state a palette entry such as {@code minecraft:chest[facing=north,type=single]} stands for, upgraded from
	 * {@code dataVersion} when {@code upgrade}.
	 */
	private static BlockState blockState(String entry, int dataVersion, boolean upgrade) {
		CompoundTag tag = new CompoundTag();
		int open = entry.indexOf('[');
		String name = open < 0 ? entry : entry.substring(0, open);
		tag.putString("Name", name);
		if (open >= 0 && entry.endsWith("]")) {
			CompoundTag properties = new CompoundTag();
			for (String pair : entry.substring(open + 1, entry.length() - 1).split(",")) {
				int equals = pair.indexOf('=');
				if (equals > 0) {
					properties.putString(pair.substring(0, equals), pair.substring(equals + 1));
				}
			}
			tag.put("Properties", properties);
		}
		if (upgrade) {
			tag = (CompoundTag) DataFixers.getDataFixer()
				.update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), dataVersion, currentDataVersion())
				.getValue();
		}
		String upgradedName = tag.getStringOr("Name", "");
		Identifier id = Identifier.tryParse(upgradedName);
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			MCVCS.LOGGER.warn("Unknown block '{}' in a schematic is read as air", upgradedName);
			return Blocks.AIR.defaultBlockState();
		}
		return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
	}

	/** {@code data}, a block entity of type {@code id} saved by {@code dataVersion}, as the running game saves it. */
	private static CompoundTag upgradeBlockEntity(CompoundTag data, String id, int dataVersion) {
		if (!id.isEmpty()) {
			data.putString("id", id);
		}
		Tag upgraded = DataFixers.getDataFixer()
			.update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, (Tag) data), dataVersion, currentDataVersion())
			.getValue();
		return upgraded instanceof CompoundTag compound ? compound : data;
	}

	/** The id of the block entity type {@code state} has, or null if it has none. */
	private static @Nullable Identifier blockEntityType(BlockState state) {
		Block block = state.getBlock();
		for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
			if (type.isValid(state)) {
				return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
			}
		}
		MCVCS.LOGGER.warn("{} has block entity data but no block entity type, so the data is not saved", block);
		return null;
	}

	private static int currentDataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}

	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	private static int readVarInt(byte[] data, int[] cursor) throws IOException {
		int value = 0;
		int shift = 0;
		while (true) {
			if (cursor[0] >= data.length) {
				throw new IOException("Schematic block data ends early");
			}
			byte b = data[cursor[0]++];
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
			if (shift > 28) {
				throw new IOException("Schematic block data has a malformed var-int");
			}
		}
	}
}
