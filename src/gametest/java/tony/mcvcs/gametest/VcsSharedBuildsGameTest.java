package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
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
import static tony.mcvcs.gametest.VcsTestSupport.teleport;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.Placement;
import tony.mcvcs.client.browser.BuildBrowser;
import tony.mcvcs.client.browser.BuildBrowserKey;
import tony.mcvcs.client.browser.BuildBrowserScreen;
import tony.mcvcs.client.browser.DeleteBuildScreen;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.network.BuildSummary;

/**
 * Every world in the game directory shares the builds in {@code mcvcs/}: a build created in one world is listed in
 * another, which can place it, under the same placement names, and commit to it. Placements stay in the world they
 * stand in. Deleting the build from one world warns about the others, removes their placements too and leaves their
 * blocks standing.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsSharedBuildsGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-shared";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) RECEIVED.add(message); });
	}

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds();
		TestWorldSave firstSave;
		String firstWorld;
		BuildBox firstBox;
		try (TestSingleplayerContext first = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			first.getClientLevel().waitForChunksRender();
			firstSave = first.getWorldSave();
			firstWorld = first.getServer().computeOnServer(Build::worldOf);

			BlockPos min = playerPos(first).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			firstBox = new BuildBox(min, max);
			fillBox(first, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			create(context, first, BUILD_NAME, min, max);
			read(schematic(BUILD_NAME, 1));
			context.waitFor(client -> ClientPlacements.selected() != null);
		}

		// A different world in the same game directory, so the same mcvcs/ folder.
		try (TestSingleplayerContext second = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			second.getClientLevel().waitForChunksRender();
			String secondWorld = second.getServer().computeOnServer(Build::worldOf);

			// The build is listed here too, but the first world's placement is not: nothing is selected or drawn.
			List<String> names = second.getServer().computeOnServer(BuildRegistry::names);
			if (!names.equals(List.of(BUILD_NAME))) {
				throw new AssertionError("Expected the first world's build to be listed in the second but got " + names);
			}
			context.waitTicks(5);
			if (context.computeOnClient(client -> ClientPlacements.selected()) != null || !context.computeOnClient(client -> ClientPlacements.all()).isEmpty()) {
				throw new AssertionError("Expected no placement of the first world to be selected or drawn in the second");
			}
			assertOnlyMessage(run(context, "vcs select " + BUILD_NAME), "Build " + BUILD_NAME + " is not placed in this world; run /vcs place " + BUILD_NAME);

			// The name is the shared build's, so creating it again is refused and v1 stays the first world's stone.
			BlockPos clash = playerPos(second).offset(2, 0, 2);
			fillBox(second, clash, clash.offset(2, 1, 1), Blocks.DIAMOND_BLOCK.defaultBlockState(), clash, Blocks.DIAMOND_BLOCK.defaultBlockState());
			assertOnlyMessage(run(context, "vcs create " + BUILD_NAME), "Build " + BUILD_NAME + " already exists; place it in this world with /vcs place " + BUILD_NAME);
			assertBlock(read(schematic(BUILD_NAME, 1)), read(schematic(BUILD_NAME, 1)).box().min(), Blocks.STONE);

			// Placed here under the same name as the first world's placement: names only need to be unique per world.
			BlockPos feet = playerPos(second).offset(0, 10, 20);
			teleport(second, feet);
			context.waitTicks(2);
			feet = playerPos(second);
			BuildBox placed = new BuildBox(feet.offset(0, -2, 0), feet.offset(2, -1, 1));
			fillBox(second, placed.min(), placed.max(), Blocks.AIR.defaultBlockState(), placed.min(), Blocks.AIR.defaultBlockState());
			assertOnlyMessage(run(context, "vcs place " + BUILD_NAME + " latest " + Build.MAIN), "Showing " + BUILD_NAME + "/" + Build.MAIN + " v1");
			assertOnlyMessage(run(context, "vcs confirmPlace"), "Placed " + BUILD_NAME + "/" + Build.MAIN + " v1");
			if (!blockAt(second, placed.min()).is(Blocks.STONE)) {
				throw new AssertionError("Expected the first world's stone at " + placed.min().toShortString() + " but got " + blockAt(second, placed.min()));
			}
			context.waitFor(client -> ClientPlacements.selected() != null);

			// A commit here is the shared build's next version; the first world's placement still holds v1, where it stood.
			setBlock(second, placed.max(), Blocks.GOLD_BLOCK.defaultBlockState());
			assertOnlyMessage(run(context, "vcs commit"), "Committed " + BUILD_NAME + "/" + Build.MAIN + " as build " + BUILD_NAME + " v2");
			assertBlock(read(schematic(BUILD_NAME, 2)), read(schematic(BUILD_NAME, 2)).box().max(), Blocks.GOLD_BLOCK);
			Placement firstMain = placementIn(firstWorld, Build.MAIN);
			if (firstMain.head() != 1 || !firstMain.origin().equals(firstBox.min())) {
				throw new AssertionError("Expected the first world's placement to stay at v1 at " + firstBox.min().toShortString() + " but got " + firstMain);
			}
			Placement secondMain = placementIn(secondWorld, Build.MAIN);
			if (secondMain.head() != 2 || !secondMain.origin().equals(placed.min())) {
				throw new AssertionError("Expected the second world's placement at v2 at " + placed.min().toShortString() + " but got " + secondMain);
			}

			lookAt(context, placed.min(), placed.max());
			screenshotLastFrame(context, "mcvcs-shared-placed");

			// Deleting from here warns that the first world's placement goes too and that its blocks stay there.
			String warning = "Warning: this build is shared with another world, '" + firstWorld
				+ "'. Its placements there will be removed too, but their blocks will stay in that world.";
			List<Component> asked = run(context, "vcs delete " + BUILD_NAME + " -c");
			assertOnlyMessage(asked, "Delete build " + BUILD_NAME + ", emptying its 1 placement in this world? This cannot be undone, all versions will be lost! "
				+ warning + " Run /vcs confirmDelete to proceed.");

			// The overlay's delete window says the same.
			context.getInput().pressKey(BuildBrowserKey.KEY);
			context.waitForScreen(BuildBrowserScreen.class);
			context.waitFor(client -> BuildBrowser.listState() == BuildBrowser.ListState.LOADED);
			context.runOnClient(client -> {
				BuildSummary summary = BuildBrowser.builds().stream().filter(build -> build.name().equals(BUILD_NAME)).findFirst()
					.orElseThrow(() -> new AssertionError("Expected " + BUILD_NAME + " in the overlay"));
				if (!summary.otherWorlds().equals(List.of(firstWorld))) {
					throw new AssertionError("Expected the overlay to know of the placement in '" + firstWorld + "' but got " + summary.otherWorlds());
				}
				client.setScreen(new DeleteBuildScreen((BuildBrowserScreen) client.screen, summary));
			});
			context.waitForScreen(DeleteBuildScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("mcvcs-shared-delete-window");
			context.runOnClient(client -> client.setScreen(null));

			// Confirming removes the whole build, empties the box here, and leaves the first world for when it is opened.
			assertOnlyMessage(run(context, "vcs confirmDelete"), "Deleted build " + BUILD_NAME + " and its 2 versions");
			if (Files.exists(BuildStorage.directory(BUILD_NAME))) {
				throw new AssertionError("Expected " + BuildStorage.directory(BUILD_NAME) + " to be deleted");
			}
			if (!blockAt(second, placed.min()).isAir()) {
				throw new AssertionError("Expected -c to empty the placement in this world but " + placed.min().toShortString() + " holds " + blockAt(second, placed.min()));
			}
		}

		// The first world has lost its placement with the build, but its blocks are still standing.
		try (TestSingleplayerContext reopened = firstSave.open()) {
			reopened.getClientLevel().waitForChunksRender();
			if (!reopened.getServer().computeOnServer(BuildRegistry::names).isEmpty()) {
				throw new AssertionError("Expected no builds after deleting the shared one");
			}
			context.waitTicks(5);
			if (context.computeOnClient(client -> ClientPlacements.selected()) != null || !context.computeOnClient(client -> ClientPlacements.all()).isEmpty()) {
				throw new AssertionError("Expected no placement left in the first world");
			}
			if (!blockAt(reopened, firstBox.min()).is(Blocks.STONE) || !blockAt(reopened, firstBox.max()).is(Blocks.STONE)) {
				throw new AssertionError("Expected the first world's blocks to stay after the build was deleted from another world");
			}
		}
	}

	/** The placement called {@code name} that the build on disk has in {@code world}. */
	private static Placement placementIn(String world, String name) {
		try {
			Optional<Placement> placement = BuildStorage.find(world, BUILD_NAME).flatMap(build -> build.placement(name));
			return placement.orElseThrow(() -> new AssertionError("Expected placement " + name + " in world '" + world + "'"));
		} catch (IOException e) {
			throw new AssertionError("Failed to read build " + BUILD_NAME, e);
		}
	}

	private static BlockState blockAt(TestSingleplayerContext singleplayer, BlockPos pos) {
		return singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(pos));
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
}
