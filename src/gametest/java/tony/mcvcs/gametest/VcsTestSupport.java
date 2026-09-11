package tony.mcvcs.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.command.VcsCommand;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Shared setup and assertions for the {@code /vcs} game tests. */
@SuppressWarnings("UnstableApiUsage")
final class VcsTestSupport {
	private VcsTestSupport() {
	}

	/** Where {@code /vcs} writes the schematic called {@code name}. */
	static Path schematic(String name) {
		WorldEdit worldEdit = WorldEdit.getInstance();
		return worldEdit.getWorkingDirectoryPath(worldEdit.getConfiguration().saveDir)
			.resolve(name + "." + VcsCommand.FORMAT.getPrimaryFileExtension());
	}

	/** Removes schematics left behind by earlier runs so a test cannot pass on stale output. */
	static void deleteSchematics(String... names) {
		for (String name : names) {
			try {
				Files.deleteIfExists(schematic(name));
			} catch (IOException e) {
				throw new AssertionError("Failed to delete stale schematic " + name, e);
			}
		}
	}

	static Clipboard read(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("Expected schematic at " + file);
		}

		try (InputStream in = Files.newInputStream(file); ClipboardReader reader = VcsCommand.FORMAT.getReader(in)) {
			return reader.read();
		} catch (IOException e) {
			throw new AssertionError("Failed to read schematic " + file, e);
		}
	}

	static BlockPos playerPos(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).blockPosition());
	}

	/** Fills the box with {@code fill}, except for {@code special} which gets {@code specialState}. */
	static void fillBox(TestSingleplayerContext singleplayer, BlockPos min, BlockPos max, BlockState fill, BlockPos special, BlockState specialState) {
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();

			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				level.setBlockAndUpdate(pos, pos.equals(special) ? specialState : fill);
			}
		});
	}

	static void setBlock(TestSingleplayerContext singleplayer, BlockPos pos, BlockState state) {
		singleplayer.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(pos, state));
	}

	/** Selects the box for the player, as {@code //pos1} and {@code //pos2} would. */
	static void select(TestSingleplayerContext singleplayer, BlockPos min, BlockPos max) {
		singleplayer.getServer().runOnServer(server -> {
			FabricAdapter adapter = FabricAdapter.get();
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			World world = adapter.fromNativeWorld(server.overworld());
			LocalSession session = WorldEdit.getInstance().getSessionManager().get(adapter.fromNativePlayer(player));
			session.setRegionSelector(world, new CuboidRegionSelector(world, adapter.adapt(min), adapter.adapt(max)));
		});
	}

	static void runCommand(ClientGameTestContext context, String command) {
		context.runOnClient(client -> client.player.connection.sendCommand(command));
		context.waitTicks(2);
	}

	static void assertOrigin(Clipboard clipboard, BlockVector3 expected) {
		if (!clipboard.getOrigin().equals(expected)) {
			throw new AssertionError("Expected origin " + expected + " but got " + clipboard.getOrigin());
		}
	}

	static void assertSize(Clipboard clipboard, BlockVector3 expected) {
		if (!clipboard.getDimensions().equals(expected)) {
			throw new AssertionError("Expected size " + expected + " but got " + clipboard.getDimensions());
		}
	}

	/** Clipboard coordinates are world coordinates, so {@code pos} is the block's position in the world. */
	static void assertBlock(Clipboard clipboard, BlockVector3 pos, BlockType expected) {
		if (clipboard.getBlock(pos).getBlockType() != expected) {
			throw new AssertionError("Expected " + expected + " at " + pos + " but got " + clipboard.getBlock(pos));
		}
	}
}
