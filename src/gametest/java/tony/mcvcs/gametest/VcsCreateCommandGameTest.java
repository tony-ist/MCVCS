package tony.mcvcs.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
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
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsCreateCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-build";

	@Override
	public void runTest(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// /vcs requires op, which in singleplayer means cheats must be on.
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 3x2x2 box in front of the player: stone everywhere except one gold block at the corner the origin is anchored to.
			BlockPos playerPos = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).blockPosition());
			BlockPos min = playerPos.offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			// Top north-west corner of the box, spelled out rather than taken from the command's own constants.
			BlockVector3 originCorner = BlockVector3.at(adapter.adapt(min).x(), adapter.adapt(max).y(), adapter.adapt(min).z());
			// The origin sits one block above the build, so a paste lands it below the player instead of around them.
			BlockVector3 expectedOrigin = originCorner.add(0, 1, 0);
			BlockPos gold = adapter.toBlockPos(originCorner);

			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();

				for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
					level.setBlockAndUpdate(pos, pos.equals(gold) ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
				}

				// Select the box for the player, as //pos1 and //pos2 would.
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				World world = adapter.fromNativeWorld(level);
				LocalSession session = WorldEdit.getInstance().getSessionManager().get(adapter.fromNativePlayer(player));
				session.setRegionSelector(world, new CuboidRegionSelector(world, adapter.adapt(min), adapter.adapt(max)));
			});

			context.runOnClient(client -> client.player.connection.sendCommand("vcs create " + BUILD_NAME));
			context.waitTicks(2);

			WorldEdit worldEdit = WorldEdit.getInstance();
			Path file = worldEdit.getWorkingDirectoryPath(worldEdit.getConfiguration().saveDir)
				.resolve(BUILD_NAME + "." + VcsCommand.FORMAT.getPrimaryFileExtension());
			if (!Files.isRegularFile(file)) {
				throw new AssertionError("Expected schematic at " + file);
			}

			Clipboard clipboard = read(file);
			if (!clipboard.getOrigin().equals(expectedOrigin)) {
				throw new AssertionError("Expected origin " + expectedOrigin + " but got " + clipboard.getOrigin());
			}

			// A paste at position `to` puts a clipboard block at `to + (block - origin)`, so check where the build's top
			// layer ends up when pasted at the player's feet: one block below them, never inside them.
			BlockVector3 pasteAt = adapter.adapt(playerPos);
			int pastedTopY = pasteAt.add(originCorner.subtract(clipboard.getOrigin())).y();
			if (pastedTopY != pasteAt.y() - 1) {
				throw new AssertionError("Expected the top layer to paste at y " + (pasteAt.y() - 1) + " but it lands at y " + pastedTopY);
			}

			BlockVector3 expectedSize = BlockVector3.at(3, 2, 2);
			if (!clipboard.getDimensions().equals(expectedSize)) {
				throw new AssertionError("Expected size " + expectedSize + " but got " + clipboard.getDimensions());
			}

			// Clipboard coordinates are world coordinates, so the gold block sits at the origin corner.
			if (clipboard.getBlock(originCorner).getBlockType() != BlockTypes.GOLD_BLOCK) {
				throw new AssertionError("Expected gold block at origin corner " + originCorner + " but got " + clipboard.getBlock(originCorner));
			}

			// The diagonally opposite corner must be stone.
			BlockVector3 opposite = adapter.adapt(min).add(adapter.adapt(max)).subtract(originCorner);
			if (clipboard.getBlock(opposite).getBlockType() != BlockTypes.STONE) {
				throw new AssertionError("Expected stone at " + opposite + " but got " + clipboard.getBlock(opposite));
			}

			context.takeScreenshot("mcvcs-vcs-create");
		}
	}

	private static Clipboard read(Path file) {
		try (InputStream in = Files.newInputStream(file); ClipboardReader reader = VcsCommand.FORMAT.getReader(in)) {
			return reader.read();
		} catch (IOException e) {
			throw new AssertionError("Failed to read schematic " + file, e);
		}
	}
}
