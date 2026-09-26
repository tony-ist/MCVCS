package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.mainBox;
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
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.preview.ClientPreview;
import tony.mcvcs.client.preview.PreviewManager;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs fit} grows the selected build's box until only air surrounds it, taking in blocks that touch it
 * diagonally and blocks that touch those, shrinks it where its sides hold only air, and saves the fitted box as the
 * next version. A build its box already fits is left alone, and one whose growth would reach into another build is
 * refused.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsFitCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-fit";
	private static final String NEIGHBOUR_NAME = "gametest-fit-neighbour";

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
			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			read(schematic(BUILD_NAME, 1));

			// Nothing touches the box and the cube fills it, so there is nothing to fit and no version is written.
			List<Component> enclosed = run(context, "vcs fit");
			assertOnlyMessage(enclosed, "Placement " + BUILD_NAME + "/" + Build.MAIN + " already fits its build; nothing to change");
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

			List<Component> grown = run(context, "vcs fit");
			assertOnlyMessage(grown, "Expanded " + BUILD_NAME + "/" + Build.MAIN + " from 2x2x2 (8 blocks) to 5x4x4 (80 blocks) and committed it as v2");
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
			screenshotLastFrame(context, "mcvcs-vcs-fit");

			// The same again is a no-op: the grown box is enclosed by air and has blocks on every side.
			List<Component> again = run(context, "vcs fit");
			assertOnlyMessage(again, "Placement " + BUILD_NAME + "/" + Build.MAIN + " already fits its build; nothing to change");
			assertNoSchematic(BUILD_NAME, 3);

			// A second build two blocks east of the first, filled with stone, and a block in the gap between them. The
			// gap block pulls the first build's box out to the neighbour's face, and the neighbour's blocks would pull it
			// through the neighbour, which is refused and leaves the first build as it was.
			BlockPos neighbourMin = new BlockPos(beyond.getX() + 2, min.getY(), min.getZ());
			BlockPos neighbourMax = neighbourMin.offset(1, 1, 1);
			BlockPos gap = new BlockPos(beyond.getX() + 1, min.getY(), min.getZ());
			fillBox(singleplayer, neighbourMin, neighbourMax, Blocks.STONE.defaultBlockState(), neighbourMin, Blocks.STONE.defaultBlockState());
			select(singleplayer, neighbourMin, neighbourMax);
			runCommand(context, "vcs create " + NEIGHBOUR_NAME + " -we");
			read(schematic(NEIGHBOUR_NAME, 1));
			setBlock(singleplayer, gap, Blocks.GOLD_BLOCK.defaultBlockState());
			runCommand(context, "vcs select " + BUILD_NAME);

			List<Component> refused = run(context, "vcs fit");
			assertOnlyMessage(refused, "Fitting " + BUILD_NAME + "/" + Build.MAIN + " to 8x4x4 would overlap " + NEIGHBOUR_NAME + "/" + Build.MAIN + "; placements may not intersect");
			assertNoSchematic(BUILD_NAME, 3);
			assertBox(context, singleplayer, BUILD_NAME, 2, expanded);

			// With the gold blocks gone, the grown box holds only the stone cube and air along its sides, so the box
			// shrinks back to the cube as v3.
			for (BlockPos gold : List.of(corner, beyond, west, gap)) {
				setBlock(singleplayer, gold, Blocks.AIR.defaultBlockState());
			}
			List<Component> shrunk = run(context, "vcs fit");
			assertOnlyMessage(shrunk, "Shrank " + BUILD_NAME + "/" + Build.MAIN + " from 5x4x4 (80 blocks) to 2x2x2 (8 blocks) and committed it as v3");
			assertBox(context, singleplayer, BUILD_NAME, 3, box);
			assertSize(read(schematic(BUILD_NAME, 3)), BlockVector3.at(2, 2, 2));

			// v2 is bigger than the shrunk box, so it is looked at through both boxes together. A block put where v2
			// had air, outside the box, stands in for something built next to the placement since.
			BlockPos outside = max.offset(1, 0, 0);
			setBlock(singleplayer, outside, Blocks.STONE.defaultBlockState());
			List<Component> diffed = run(context, "vcs diff 2");
			assertOnlyMessage(diffed, "4 blocks in " + BUILD_NAME + "/" + Build.MAIN + " differ from v2 (1 added, 3 removed, 0 changed)");
			context.waitFor(client -> DiffManager.active() != null);
			BuildBox diffBox = context.computeOnClient(client -> DiffManager.active().diff().box());
			if (!diffBox.equals(expanded)) {
				throw new AssertionError("Expected the diff against v2 to cover " + expanded + " but got " + diffBox);
			}
			runCommand(context, "vcs diff off");

			// The preview of v2 covers the same box: the gold blocks are back and the block next to the placement,
			// which v2 does not have, is hidden.
			runCommand(context, "vcs preview 2");
			context.waitFor(client -> PreviewManager.active() != null);
			ClientPreview preview = context.computeOnClient(client -> PreviewManager.active());
			if (!preview.box().equals(expanded) || !preview.stateAt(corner).is(Blocks.GOLD_BLOCK) || !preview.stateAt(west).is(Blocks.GOLD_BLOCK)
				|| !preview.stateAt(outside).isAir() || !preview.stateAt(min).is(Blocks.STONE)) {
				throw new AssertionError("Expected the preview of v2 to show it over " + expanded + " but got " + preview);
			}
			lookAt(context, west, beyond);
			screenshotLastFrame(context, "mcvcs-vcs-fit-preview");
			runCommand(context, "vcs preview off");
			context.waitFor(client -> PreviewManager.active() == null);
			setBlock(singleplayer, outside, Blocks.AIR.defaultBlockState());

			// Digging out the cube's east face and putting a block against its west one grows the box on one side and
			// shrinks it on the other in the same fit.
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(max.getX(), min.getY(), min.getZ()), max)) {
				setBlock(singleplayer, pos.immutable(), Blocks.AIR.defaultBlockState());
			}
			setBlock(singleplayer, west, Blocks.GOLD_BLOCK.defaultBlockState());
			List<Component> moved = run(context, "vcs fit");
			assertOnlyMessage(moved, "Fitted " + BUILD_NAME + "/" + Build.MAIN + " from 2x2x2 (8 blocks) to 2x2x2 (8 blocks) and committed it as v4");
			assertBox(context, singleplayer, BUILD_NAME, 4, new BuildBox(west, max.offset(-1, 0, 0)));

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
		if (build.isEmpty() || build.get().version() != version || !mainBox(build.get()).equals(box)) {
			throw new AssertionError("Expected build '" + name + "' v" + version + " with box " + box + " but got " + build.orElse(null));
		}
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name) && selected.head() == version;
		});
		ClientPlacement shown = context.computeOnClient(client -> ClientPlacements.selected());
		if (!shown.box().equals(box)) {
			throw new AssertionError("Expected the client to show box " + box + " for '" + name + "' but got " + shown.box());
		}
	}

	/**
	 * A gold block at the box's corner and another one diagonally past it want two steps of growth: the first fits
	 * within {@link BoxExpansion#MAX_VOLUME}, the second does not. The expansion is given up as a whole, so the box
	 * stays as it was rather than take the first step, and reports it is not enclosed.
	 */
	private static void assertLimit(TestSingleplayerContext singleplayer, BuildBox box) {
		// One short of the largest cube within MAX_VOLUME, hovering at the same height as the build but well south of
		// everything the test placed, so nothing but the gold blocks put at its corner touches it. One more block on
		// every side still fits the limit, two go past it.
		int side = (int) Math.cbrt(BoxExpansion.MAX_VOLUME) - 1;
		BlockPos min = box.min().offset(0, 0, 20);
		BuildBox below = new BuildBox(min, min.offset(side - 1, side - 1, side - 1));
		BlockPos corner = below.max().offset(1, 1, 1);
		BlockPos beyond = below.max().offset(2, 2, 2);
		if (new BuildBox(min, corner).volume() > BoxExpansion.MAX_VOLUME || new BuildBox(min, beyond).volume() <= BoxExpansion.MAX_VOLUME) {
			throw new AssertionError("Test box " + below + " does not sit one step below the limit");
		}

		BoxExpansion expansion = singleplayer.getServer().computeOnServer(server -> {
			server.overworld().setBlockAndUpdate(corner, Blocks.GOLD_BLOCK.defaultBlockState());
			server.overworld().setBlockAndUpdate(beyond, Blocks.GOLD_BLOCK.defaultBlockState());
			return BoxExpansion.of(below, server.overworld());
		});
		if (!expansion.to().equals(below) || expansion.enclosed() || expansion.grew()) {
			throw new AssertionError("Expected the limit to hold " + below + " but got " + expansion);
		}

		// With only the first block the single step fits and is taken.
		BoxExpansion oneStep = singleplayer.getServer().computeOnServer(server -> {
			server.overworld().setBlockAndUpdate(beyond, Blocks.AIR.defaultBlockState());
			return BoxExpansion.of(below, server.overworld());
		});
		if (!oneStep.to().equals(new BuildBox(min, corner)) || !oneStep.enclosed()) {
			throw new AssertionError("Expected " + below + " to grow to " + corner + " but got " + oneStep);
		}

		// Without any block the box is enclosed and stays.
		BoxExpansion clear = singleplayer.getServer().computeOnServer(server -> {
			server.overworld().setBlockAndUpdate(corner, Blocks.AIR.defaultBlockState());
			return BoxExpansion.of(below, server.overworld());
		});
		if (!clear.to().equals(below) || !clear.enclosed()) {
			throw new AssertionError("Expected " + below + " to be enclosed but got " + clear);
		}
	}
}
