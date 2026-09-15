package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.ClientBuild;
import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.client.diff.DiffManager;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs expand} grows the selected build's box until only air surrounds it, taking in blocks that touch it
 * diagonally and blocks that touch those, and saves the grown box as the next version. A build already enclosed by
 * air is left alone, and one whose growth would reach into another build is refused.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsExpandCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-expand";
	private static final String NEIGHBOUR_NAME = "gametest-expand-neighbour";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME, NEIGHBOUR_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 2x2x2 stone cube hovering one block above the ground in front of and to the right of the player, so
			// nothing but air touches it. Expanding a box that stood on the ground would take the ground with it.
			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(1, 1, 1);
			BuildBox box = new BuildBox(min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));

			// Nothing touches the box, so there is nothing to expand and no version is written.
			List<Component> enclosed = run(context, "vcs expand");
			assertOnlyMessage(enclosed, "Build " + BUILD_NAME + " is already enclosed by air; nothing to expand");
			assertNoSchematic(BUILD_NAME, 2);
			assertBox(context, singleplayer, BUILD_NAME, 1, box);

			// A gold block touching the top south-east corner only diagonally, a second one touching that one the same
			// way, and one against the west face. The box has to grow to the far corner of the chain and one block west.
			BlockPos corner = max.offset(1, 1, 1);
			BlockPos beyond = max.offset(2, 2, 2);
			BlockPos west = min.offset(-1, 0, 0);
			setBlock(singleplayer, corner, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, beyond, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, west, Blocks.GOLD_BLOCK.defaultBlockState());
			BuildBox expanded = new BuildBox(west, beyond);

			List<Component> grown = run(context, "vcs expand");
			assertOnlyMessage(grown, "Expanded build " + BUILD_NAME + " from 2x2x2 (8 blocks) to 5x4x4 (80 blocks) and committed it as v2");
			assertBox(context, singleplayer, BUILD_NAME, 2, expanded);

			// v2 is the grown box as the world has it: the stone cube, the three gold blocks and air around them.
			Clipboard clipboard = read(schematic(BUILD_NAME, 2));
			assertSize(clipboard, BlockVector3.at(5, 4, 4));
			assertBlock(clipboard, adapter.adapt(min), BlockTypes.STONE);
			assertBlock(clipboard, adapter.adapt(max), BlockTypes.STONE);
			assertBlock(clipboard, adapter.adapt(corner), BlockTypes.GOLD_BLOCK);
			assertBlock(clipboard, adapter.adapt(beyond), BlockTypes.GOLD_BLOCK);
			assertBlock(clipboard, adapter.adapt(west), BlockTypes.GOLD_BLOCK);
			assertBlock(clipboard, adapter.adapt(max.offset(1, 0, 0)), BlockTypes.AIR);

			// The box and its latest schematic agree, so a diff against v2 has nothing to show.
			runCommand(context, "vcs diff");
			context.waitTicks(5);
			if (context.computeOnClient(client -> DiffManager.active()) != null) {
				throw new AssertionError("Expected the world to match v2 right after expanding");
			}

			lookAt(context, west, beyond);
			screenshotLastFrame(context, "mcvcs-vcs-expand");

			// The same again is a no-op: the grown box is enclosed by air.
			List<Component> again = run(context, "vcs expand");
			assertOnlyMessage(again, "Build " + BUILD_NAME + " is already enclosed by air; nothing to expand");
			assertNoSchematic(BUILD_NAME, 3);

			// A second build two blocks east of the first, filled with stone, and a block in the gap between them. The
			// gap block pulls the first build's box out to the neighbour's face, and the neighbour's blocks would pull it
			// through the neighbour, which is refused and leaves the first build as it was.
			BlockPos neighbourMin = new BlockPos(beyond.getX() + 2, min.getY(), min.getZ());
			BlockPos neighbourMax = neighbourMin.offset(1, 1, 1);
			BlockPos gap = new BlockPos(beyond.getX() + 1, min.getY(), min.getZ());
			fillBox(singleplayer, neighbourMin, neighbourMax, Blocks.STONE.defaultBlockState(), neighbourMin, Blocks.STONE.defaultBlockState());
			select(singleplayer, neighbourMin, neighbourMax);
			runCommand(context, "vcs create " + NEIGHBOUR_NAME);
			read(schematic(NEIGHBOUR_NAME, 1));
			setBlock(singleplayer, gap, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs select " + BUILD_NAME);

			List<Component> refused = run(context, "vcs expand");
			assertOnlyMessage(refused, "Expanding build " + BUILD_NAME + " to 8x4x4 would overlap build " + NEIGHBOUR_NAME + "; builds may not intersect");
			assertNoSchematic(BUILD_NAME, 3);
			assertBox(context, singleplayer, BUILD_NAME, 2, expanded);

			// Without the world: the limit holds the box where it is and says so.
			assertLimit(singleplayer, expanded);
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

	private static void assertNoSchematic(String name, int version) {
		if (Files.exists(schematic(name, version))) {
			throw new AssertionError("Expected no " + schematic(name, version));
		}
	}

	/** The build on disk and, once the server has told it, the client's copy both have the given version and box. */
	private static void assertBox(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, int version, BuildBox box) {
		Optional<Build> build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, name));
		if (build.isEmpty() || build.get().version() != version || !build.get().box().equals(box)) {
			throw new AssertionError("Expected build '" + name + "' v" + version + " with box " + box + " but got " + build.orElse(null));
		}
		context.waitFor(client -> {
			ClientBuild selected = ClientBuilds.selected();
			return selected != null && selected.name().equals(name) && selected.version() == version;
		});
		ClientBuild shown = context.computeOnClient(client -> ClientBuilds.selected());
		if (!shown.box().equals(box)) {
			throw new AssertionError("Expected the client to show box " + box + " for '" + name + "' but got " + shown.box());
		}
	}

	/**
	 * With the block at the box's corner in place, one step of growth is wanted, but a box that is already at
	 * {@link BoxExpansion#MAX_VOLUME} cannot take it: the box stays and the expansion reports it is not enclosed.
	 */
	private static void assertLimit(TestSingleplayerContext singleplayer, BuildBox box) {
		// The largest cube within MAX_VOLUME, hovering at the same height as the build but well south of everything
		// the test placed, so nothing but the gold block put at its corner touches it. One more block on every side
		// would go past the limit.
		int side = (int) Math.cbrt(BoxExpansion.MAX_VOLUME);
		BlockPos min = box.min().offset(0, 0, 20);
		BuildBox atLimit = new BuildBox(min, min.offset(side - 1, side - 1, side - 1));
		BlockPos corner = atLimit.max().offset(1, 1, 1);
		if (atLimit.volume() > BoxExpansion.MAX_VOLUME || new BuildBox(min, corner).volume() <= BoxExpansion.MAX_VOLUME) {
			throw new AssertionError("Test box " + atLimit + " does not sit at the limit");
		}

		BoxExpansion expansion = singleplayer.getServer().computeOnServer(server -> {
			server.overworld().setBlockAndUpdate(corner, Blocks.GOLD_BLOCK.defaultBlockState());
			return BoxExpansion.of(atLimit, server.overworld());
		});
		if (!expansion.to().equals(atLimit) || expansion.enclosed() || expansion.grew()) {
			throw new AssertionError("Expected the limit to hold " + atLimit + " but got " + expansion);
		}

		// Without the block the same box is enclosed and stays as well.
		BoxExpansion clear = singleplayer.getServer().computeOnServer(server -> {
			server.overworld().setBlockAndUpdate(corner, Blocks.AIR.defaultBlockState());
			return BoxExpansion.of(atLimit, server.overworld());
		});
		if (!clear.to().equals(atLimit) || !clear.enclosed()) {
			throw new AssertionError("Expected " + atLimit + " to be enclosed but got " + clear);
		}
	}
}
