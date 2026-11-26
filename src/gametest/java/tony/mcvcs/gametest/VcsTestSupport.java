package tony.mcvcs.gametest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.build.Schematic;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.command.VcsCommandCreate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Shared setup and assertions for the {@code /vcs} game tests. */
@SuppressWarnings("UnstableApiUsage")
final class VcsTestSupport {
	private VcsTestSupport() {
	}

	/** The {@code main} placement of {@code build}, the one {@code /vcs create} makes and the only one most tests have. */
	static BuildPlacement main(Build build) {
		return BuildPlacement.of(build, Build.MAIN).orElseThrow(() -> new AssertionError("Build '" + build.name() + "' has no '" + Build.MAIN + "' placement, only " + build.placementNames()));
	}

	/** The world box of {@code build}'s {@code main} placement. */
	static BuildBox mainBox(Build build) {
		return main(build).box();
	}

	/** Where {@code /vcs} writes version {@code version} of the build called {@code name}, spelled out rather than taken from the mod. */
	@SuppressWarnings("SameParameterValue")
	static Path schematic(String name, int version) {
		return FabricLoader.getInstance().getGameDir().resolve("mcvcs").resolve(name).resolve(name + "-v" + version + ".schem");
	}

	/** Where {@code /vcs} writes the description of the build called {@code name}, spelled out rather than taken from the mod. */
	static Path buildFile(String name) {
		return FabricLoader.getInstance().getGameDir().resolve("mcvcs").resolve(name).resolve("build.json");
	}

	/**
	 * Removes every build and every player's selection left behind by earlier tests and runs, so a test cannot pass
	 * on stale output. Builds and selections are stored in the game directory, not with the world, and every world
	 * sees every build, so a fresh world alone does not clear them.
	 */
	static void resetBuilds() {
		Path root = BuildStorage.root();
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException e) {
			throw new AssertionError("Failed to delete stale builds in " + root, e);
		}
	}

	static Schematic read(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("Expected schematic at " + file);
		}

		try {
			return BuildStorage.readSchematic(file);
		} catch (IOException e) {
			throw new AssertionError("Failed to read schematic " + file, e);
		}
	}

	static BlockPos playerPos(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).blockPosition());
	}

	/** Puts the player at {@code pos}, feet first, so commands that go by where they stand have somewhere to work. */
	static void teleport(TestSingleplayerContext singleplayer, BlockPos pos) {
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0)
			.teleportTo(server.overworld(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, java.util.Set.of(), 0, 0, true));
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

	/** Puts {@code stack} into slot {@code slot} of the container block at {@code pos}, as a player would through its screen. */
	static void putItem(TestSingleplayerContext singleplayer, BlockPos pos, int slot, ItemStack stack) {
		singleplayer.getServer().runOnServer(server -> {
			if (!(server.overworld().getBlockEntity(pos) instanceof Container container)) {
				throw new AssertionError("Expected a container at " + pos + " but found " + server.overworld().getBlockState(pos));
			}
			container.setItem(slot, stack);
			container.setChanged();
		});
	}

	/**
	 * Creates the build called {@code name} with a {@code main} placement covering exactly the box from {@code min} to
	 * {@code max} in the overworld, as {@code /vcs create} would after a punch whose blocks grew to that box. The
	 * player gets the same feedback in chat.
	 */
	static void create(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, BlockPos min, BlockPos max) {
		create(context, singleplayer, name, Build.MAIN, min, max);
	}

	/** {@link #create(ClientGameTestContext, TestSingleplayerContext, String, BlockPos, BlockPos)} with a placement name of its own. */
	static void create(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, String placement, BlockPos min, BlockPos max) {
		int created = singleplayer.getServer().computeOnServer(server -> VcsCommandCreate.createFromBox(
			server.getPlayerList().getPlayers().get(0), server.overworld(), name, placement, new BuildBox(min, max)));
		if (created == 1) {
			// The selection goes out just before the reply in chat, so once the client has one the other is on its way.
			context.waitFor(client -> {
				ClientPlacement selected = ClientPlacements.selected();
				return selected != null && selected.build().equals(name) && selected.placement().equals(placement);
			});
		}
		context.waitTicks(5);
	}

	/** What the server has at {@code pos} in the overworld. */
	static BlockState blockAt(TestSingleplayerContext singleplayer, BlockPos pos) {
		return singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(pos));
	}

	static void runCommand(ClientGameTestContext context, String command) {
		context.runOnClient(client -> client.player.connection.sendCommand(command));
		context.waitTicks(2);
	}

	/** What the server offers to complete {@code command} (without the leading slash) with, as the player would see. */
	static List<String> suggestions(TestSingleplayerContext singleplayer, String command) {
		return singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
			ParseResults<CommandSourceStack> parse = dispatcher.parse(command, player.createCommandSourceStack());
			return dispatcher.getCompletionSuggestions(parse).join().getList().stream().map(Suggestion::getText).toList();
		});
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

	/**
	 * What the action bar is showing, or {@code null} when it is showing nothing. Reaches into the HUD by reflection,
	 * as nothing public leads to the message a {@code sendOverlayMessage} left there.
	 */
	static String overlayMessage(Minecraft client) {
		try {
			Field field = Gui.class.getDeclaredField("overlayMessageString");
			field.setAccessible(true);
			Component message = (Component) field.get(client.gui);
			return message == null ? null : message.getString();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to read the action bar message", e);
		}
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

	/**
	 * Whether the renderer has built geometry for the chunk section around {@code pos}, i.e. something in it is
	 * actually drawn. {@code LevelRenderer.isSectionCompiledAndVisible} is not enough: a section the renderer skips
	 * as empty counts as compiled there while drawing nothing. Reaches into the renderer's view area by reflection,
	 * as nothing public leads from a position to its section.
	 */
	static boolean sectionHasGeometry(Minecraft client, BlockPos pos) {
		try {
			Field viewAreaField = LevelRenderer.class.getDeclaredField("viewArea");
			viewAreaField.setAccessible(true);
			ViewArea viewArea = (ViewArea) viewAreaField.get(client.levelRenderer);
			Method getRenderSectionAt = ViewArea.class.getDeclaredMethod("getRenderSectionAt", BlockPos.class);
			getRenderSectionAt.setAccessible(true);
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) getRenderSectionAt.invoke(viewArea, pos);
			return section != null && section.getSectionMesh().hasRenderableLayers();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to reach the render section at " + pos, e);
		}
	}

	static void assertOrigin(Schematic schematic, BlockPos expected) {
		if (!schematic.origin().equals(expected)) {
			throw new AssertionError("Expected origin " + expected + " but got " + schematic.origin());
		}
	}

	static void assertSize(Schematic schematic, Vec3i expected) {
		BuildBox box = schematic.box();
		Vec3i size = new Vec3i(box.sizeX(), box.sizeY(), box.sizeZ());
		if (!size.equals(expected)) {
			throw new AssertionError("Expected size " + expected + " but got " + size);
		}
	}

	/** Schematic coordinates are world coordinates, so {@code pos} is the block's position in the world. */
	static void assertBlock(Schematic schematic, BlockPos pos, Block expected) {
		if (!schematic.box().contains(pos)) {
			throw new AssertionError("Expected " + expected + " at " + pos + " but the schematic only covers " + schematic.box());
		}
		BlockState actual = schematic.blocks().stateAt(pos);
		if (!actual.is(expected)) {
			throw new AssertionError("Expected " + expected + " at " + pos + " but got " + actual);
		}
	}
}
