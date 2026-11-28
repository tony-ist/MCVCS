package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.buildFile;
import static tony.mcvcs.gametest.VcsTestSupport.create;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;

/**
 * {@code /vcs rename <buildname> <newname>} moves the build's folder and schematics to the new name and rewrites the
 * name in {@code build.json}, keeping its versions and tags; the player's selection follows it, in this world and in
 * any other, and the client hears the new name. A missing build, an invalid name and one taken by another build are
 * refused; a rename that only changes letter case works.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsRenameCommandGameTest extends VcsGameTest {
	private static final String OLD_NAME = "gametest-rename";
	private static final String NEW_NAME = "gametest-renamed";
	private static final String CASED_NAME = "gametest-Renamed";
	private static final String OTHER_NAME = "gametest-rename-other";
	private static final String OTHER_WORLD = "gametest-rename-other-world";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) RECEIVED.add(message); });
	}

	@Override
	protected void run(ClientGameTestContext context) {
		resetBuilds();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Another build, whose name the rename may not take.
			BlockPos otherMin = playerPos(singleplayer).offset(-6, 0, 2);
			BlockPos otherMax = otherMin.offset(1, 1, 1);
			fillBox(singleplayer, otherMin, otherMax, Blocks.OAK_PLANKS.defaultBlockState(), otherMin, Blocks.OAK_PLANKS.defaultBlockState());
			create(context, singleplayer, OTHER_NAME, otherMin, otherMax);

			// A 3x2x2 stone box saved as two versions, the second tagged, and selected.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			create(context, singleplayer, OLD_NAME, min, max);
			read(schematic(OLD_NAME, 1));
			setBlock(singleplayer, max, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs save 2.0.0");
			read(schematic(OLD_NAME, 2));
			waitForSelection(context, OLD_NAME);

			// A selection of the build in another world, which the rename must carry along too.
			UUID player = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).getUUID());
			singleplayer.getServer().runOnServer(server -> {
				try {
					BuildStorage.saveSelection(OTHER_WORLD, player, new BuildStorage.Selection(OLD_NAME, Build.MAIN));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});

			// Refusals leave everything as it was.
			assertOnlyMessage(run(context, "vcs rename " + OLD_NAME + "-missing " + NEW_NAME), "No build named " + OLD_NAME + "-missing");
			assertOnlyMessage(run(context, "vcs rename " + OLD_NAME + " 1" + NEW_NAME), "Build name 1" + NEW_NAME + " is not allowed: start with a letter");
			assertOnlyMessage(run(context, "vcs rename " + OLD_NAME + " " + OTHER_NAME.toUpperCase()), "Build " + OTHER_NAME + " already exists");
			assertOnlyMessage(run(context, "vcs rename " + OLD_NAME + " " + OLD_NAME), "Build " + OLD_NAME + " is already called that");
			assertBuildOnDisk(OLD_NAME);

			// The rename moves everything and the selection follows it.
			assertOnlyMessage(run(context, "vcs rename " + OLD_NAME + " " + NEW_NAME), "Renamed build " + OLD_NAME + " to " + NEW_NAME);
			assertBuildOnDisk(NEW_NAME);
			if (Files.exists(BuildStorage.root().resolve(OLD_NAME))) {
				throw new AssertionError("Expected the folder " + OLD_NAME + " to be gone after the rename");
			}
			waitForSelection(context, NEW_NAME);
			assertSelectedOnServer(singleplayer, NEW_NAME);
			assertSelectionIn(singleplayer, OTHER_WORLD, player, NEW_NAME);

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-rename");

			// Changing only the case works too, even on a file system that does not tell the two apart.
			assertOnlyMessage(run(context, "vcs rename " + NEW_NAME + " " + CASED_NAME), "Renamed build " + NEW_NAME + " to " + CASED_NAME);
			assertBuildOnDisk(CASED_NAME);
			waitForSelection(context, CASED_NAME);
			assertSelectionIn(singleplayer, OTHER_WORLD, player, CASED_NAME);

			// The renamed build keeps working: a commit saves its v3 under the new name.
			setBlock(singleplayer, min, Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs save");
			context.waitFor(client -> Files.isRegularFile(schematic(CASED_NAME, 3)));
			read(schematic(CASED_NAME, 3));
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	private static void assertOnlyMessage(List<Component> messages, String prefix) {
		if (messages.size() != 1 || !messages.get(0).getString().startsWith(prefix)) {
			throw new AssertionError("Expected only a message starting with '" + prefix + "' but got " + messages.stream().map(Component::getString).toList());
		}
	}

	/** The build's folder is named exactly {@code name}, holds both schematics under that name, and its build.json says so. */
	private static void assertBuildOnDisk(String name) {
		List<String> folders;
		try (Stream<Path> children = Files.list(BuildStorage.root())) {
			folders = children.map(path -> path.getFileName().toString()).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!folders.contains(name)) {
			throw new AssertionError("Expected a folder named exactly " + name + " but found " + folders);
		}
		List<String> files;
		try (Stream<Path> children = Files.list(BuildStorage.directory(name))) {
			files = children.map(path -> path.getFileName().toString()).sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		List<String> expected = List.of(BuildStorage.BUILD_FILE, name + "-v1.schem", name + "-v2.schem");
		if (!files.equals(expected)) {
			throw new AssertionError("Expected " + expected + " in " + BuildStorage.directory(name) + " but found " + files);
		}
		read(schematic(name, 1));
		read(schematic(name, 2));
		String json;
		try {
			json = Files.readString(buildFile(name));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!json.contains("\"name\": \"" + name + "\"") || !json.contains("\"2.0.0\": 2")) {
			throw new AssertionError("Expected " + buildFile(name) + " to name " + name + " and keep tag 2.0.0 but it reads " + json);
		}
	}

	private static void assertSelectedOnServer(TestSingleplayerContext singleplayer, String name) {
		Optional<BuildPlacement> selected = singleplayer.getServer().computeOnServer(server -> BuildRegistry.selected(server.getPlayerList().getPlayers().get(0)));
		if (selected.isEmpty() || !selected.get().build().name().equals(name) || selected.get().head() != 2) {
			throw new AssertionError("Expected selection '" + name + "' v2 on the server but got " + selected.orElse(null));
		}
	}

	private static void assertSelectionIn(TestSingleplayerContext singleplayer, String world, UUID player, String name) {
		Optional<BuildStorage.Selection> selection = singleplayer.getServer().computeOnServer(server -> {
			try {
				return BuildStorage.selection(world, player);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		if (selection.isEmpty() || !selection.get().build().equals(name)) {
			throw new AssertionError("Expected the selection in " + world + " to name " + name + " but got " + selection.orElse(null));
		}
	}

	private static void waitForSelection(ClientGameTestContext context, String name) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name) && selected.head() == 2;
		});
	}
}
