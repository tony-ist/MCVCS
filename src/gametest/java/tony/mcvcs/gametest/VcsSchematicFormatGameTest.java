package tony.mcvcs.gametest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.Schematic;
import tony.mcvcs.build.SpongeSchematic;

/**
 * Schematics are read and written by the mod itself: one written by WorldEdit, as every version saved before the mod
 * stopped using it was, reads with its origin, box, blocks and block entities; writing it out and reading it back
 * gives the same schematic; and one saved by an older game is upgraded on the way in.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsSchematicFormatGameTest extends VcsGameTest {
	/** A 2x1x3 row of observers and barrels, the barrels empty, saved by WorldEdit 7.4 on this game version. */
	private static final String WORLDEDIT_FILE = "/schematics/worldedit-barrels.schem";
	/** The data version of 1.20.1, before {@code minecraft:grass} was renamed {@code minecraft:short_grass}. */
	private static final int DATA_VERSION_1_20_1 = 3465;

	@Override
	protected void run(ClientGameTestContext context) {
		byte[] file = resource(WORLDEDIT_FILE);
		Schematic worldEdit = read(file);

		// WorldEdit keeps the origin in its metadata and the box as an offset from it.
		BlockPos origin = new BlockPos(-176, 76, 122);
		BuildBox box = new BuildBox(new BlockPos(-176, 75, 122), new BlockPos(-175, 75, 124));
		check(worldEdit.origin().equals(origin), "origin " + worldEdit.origin() + ", expected " + origin);
		check(worldEdit.box().equals(box), "box " + worldEdit.box() + ", expected " + box);

		BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH);
		BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
		BlockPos min = box.min();
		for (int z = 0; z < 3; z++) {
			assertState(worldEdit, min.offset(0, 0, z), observer);
		}
		assertState(worldEdit, min, observer);
		assertState(worldEdit, min.offset(1, 0, 0), Blocks.AIR.defaultBlockState());
		assertState(worldEdit, min.offset(1, 0, 1), barrel);
		assertState(worldEdit, min.offset(1, 0, 2), barrel);
		// Each barrel keeps its saved data, without the id and position that only say what and where it is.
		for (BlockPos pos : new BlockPos[]{min.offset(1, 0, 1), min.offset(1, 0, 2)}) {
			CompoundTag data = worldEdit.blocks().dataAt(pos);
			check(data != null && data.contains("Items") && !data.contains("id"), "barrel data at " + pos + ": " + data);
		}
		check(worldEdit.blocks().dataAt(min) == null, "an observer has no block entity data");

		// Written out and read back, nothing changes.
		Schematic roundTrip = read(write(worldEdit));
		check(roundTrip.equals(worldEdit), "round trip changed the schematic: " + roundTrip + " vs " + worldEdit);

		// The same file claiming to come from 1.20.1 goes through the data fixer and still reads the same blocks.
		CompoundTag root = nbt(file);
		root.getCompoundOrEmpty("Schematic").putInt("DataVersion", DATA_VERSION_1_20_1);
		Schematic upgraded = read(bytes(root));
		check(Objects.equals(upgraded.blocks().box(), worldEdit.blocks().box()), "upgraded box " + upgraded.box());
		for (int index = 0; index < worldEdit.blocks().size(); index++) {
			check(upgraded.blocks().state(index) == worldEdit.blocks().state(index), "upgraded block " + index + ": " + upgraded.blocks().state(index));
			check((upgraded.blocks().data(index) == null) == (worldEdit.blocks().data(index) == null), "upgraded block entity " + index);
		}

		// A block that was renamed since then comes back under its new name, and one the game does not know reads as air.
		CompoundTag old = nbt(file);
		CompoundTag schematic = old.getCompoundOrEmpty("Schematic");
		schematic.putInt("DataVersion", DATA_VERSION_1_20_1);
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:grass", 0);
		palette.putInt("minecraft:air", 1);
		palette.putInt("minecraft:observer[facing=north,powered=false]", 2);
		palette.putInt("othermod:gone", 3);
		CompoundTag blocks = schematic.getCompoundOrEmpty("Blocks");
		blocks.put("Palette", palette);
		// Data runs x fastest, then z, then y: (0,0,0) (1,0,0) (0,0,1) (1,0,1) (0,0,2) (1,0,2).
		blocks.putByteArray("Data", new byte[]{0, 1, 2, 3, 2, 1});
		blocks.put("BlockEntities", new net.minecraft.nbt.ListTag());
		Schematic renamed = read(bytes(old));
		assertState(renamed, min, Blocks.SHORT_GRASS.defaultBlockState());
		assertState(renamed, min.offset(0, 0, 1), observer);
		assertState(renamed, min.offset(1, 0, 1), Blocks.AIR.defaultBlockState());
	}

	private static void assertState(Schematic schematic, BlockPos pos, BlockState expected) {
		BlockState actual = schematic.blocks().stateAt(pos);
		check(actual == expected, "block at " + pos + " is " + actual + ", expected " + expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static byte[] resource(String name) {
		try (InputStream in = VcsSchematicFormatGameTest.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new AssertionError("Missing test resource " + name);
			}
			return in.readAllBytes();
		} catch (IOException e) {
			throw new AssertionError("Failed to read test resource " + name, e);
		}
	}

	private static Schematic read(byte[] file) {
		try {
			return SpongeSchematic.read(new ByteArrayInputStream(file));
		} catch (IOException e) {
			throw new AssertionError("Failed to read schematic", e);
		}
	}

	private static byte[] write(Schematic schematic) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			SpongeSchematic.write(schematic, out);
		} catch (IOException e) {
			throw new AssertionError("Failed to write schematic", e);
		}
		return out.toByteArray();
	}

	private static CompoundTag nbt(byte[] file) {
		try {
			return NbtIo.readCompressed(new ByteArrayInputStream(file), NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new AssertionError("Failed to read NBT", e);
		}
	}

	private static byte[] bytes(CompoundTag root) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			NbtIo.writeCompressed(root, out);
		} catch (IOException e) {
			throw new AssertionError("Failed to write NBT", e);
		}
		return out.toByteArray();
	}
}
