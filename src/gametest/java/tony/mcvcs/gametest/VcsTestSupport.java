package tony.mcvcs.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;

import tony.mcvcs.project.ProjectStorage;
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

	/** Where {@code /vcs} writes version {@code version} of the build called {@code name}, spelled out rather than taken from the mod. */
	@SuppressWarnings("SameParameterValue")
	static Path schematic(String name, int version) {
		return FabricLoader.getInstance().getGameDir().resolve("mcvcs").resolve(name).resolve("v" + version + ".schem");
	}

	/** Where {@code /vcs} writes the description of the build called {@code name}, spelled out rather than taken from the mod. */
	static Path projectFile(String name) {
		return FabricLoader.getInstance().getGameDir().resolve("mcvcs").resolve(name).resolve("project.json");
	}

	/**
	 * Removes the given project folders and every player's selection left behind by earlier runs, so a test cannot
	 * pass on stale output. Projects and selections are stored in the game directory, not with the world, so a
	 * fresh world alone does not clear them; each test uses names of its own and worlds get fresh save folders, so
	 * this mostly matters when a save folder name is reused between runs.
	 */
	static void resetProjects(String... names) {
		try {
			Files.deleteIfExists(ProjectStorage.selectionsFile());
		} catch (IOException e) {
			throw new AssertionError("Failed to delete stale selections", e);
		}
		for (String name : names) {
			Path directory = ProjectStorage.directory(name);
			if (!Files.exists(directory)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(directory)) {
				for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(file);
				}
			} catch (IOException e) {
				throw new AssertionError("Failed to delete stale project folder " + directory, e);
			}
		}
	}

	static Clipboard read(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("Expected schematic at " + file);
		}

		try (InputStream in = Files.newInputStream(file); ClipboardReader reader = ProjectStorage.FORMAT.getReader(in)) {
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

	/**
	 * Saves what the last normally rendered frame looks like, to {@code build/run/clientGameTest/screenshots/<name>.png}.
	 * <p>
	 * Unlike {@link ClientGameTestContext#takeScreenshot(String)}, this does not render an extra frame. That extra
	 * frame reuses the previous extraction but the per-frame gizmo collector has already been drained, so anything
	 * drawn through {@link net.minecraft.gizmos.Gizmos}, such as the selection box, is missing from it.
	 */
	static Path screenshotLastFrame(ClientGameTestContext context, String name) {
		Path file = FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve(name + ".png");
		CompletableFuture<Void> saved = context.computeOnClient(client -> {
			CompletableFuture<Void> future = new CompletableFuture<>();
			Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
				try (image) {
					Files.createDirectories(file.getParent());
					image.writeToFile(file);
					future.complete(null);
				} catch (IOException e) {
					future.completeExceptionally(e);
				}
			});
			return future;
		});

		while (!saved.isDone()) {
			context.waitTick();
		}
		saved.join();
		return file;
	}

	/** Turns the player toward the centre of the box so screenshots show it. */
	static void lookAt(ClientGameTestContext context, BlockPos min, BlockPos max) {
		context.runOnClient(client -> {
			double x = (min.getX() + max.getX() + 1) / 2.0 - client.player.getX();
			double y = (min.getY() + max.getY() + 1) / 2.0 - client.player.getEyeY();
			double z = (min.getZ() + max.getZ() + 1) / 2.0 - client.player.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
			float pitch = (float) Math.toDegrees(-Math.atan2(y, Math.sqrt(x * x + z * z)));
			client.player.setYRot(yaw);
			client.player.setXRot(pitch);
		});
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
