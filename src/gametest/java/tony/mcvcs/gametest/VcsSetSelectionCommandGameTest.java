package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.clearSelection;
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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;
import com.sk89q.worldedit.math.BlockVector3;

/**
 * {@code /vcs setSelection} gives the selected build the bounding box of the player's WorldEdit selection and saves it
 * as the next version, whether the new box is bigger or smaller, warning about blocks the new box leaves out. It
 * refuses without a selected build or a WorldEdit selection, leaves a build whose box already is the selection alone,
 * and refuses a box that would reach into another build.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsSetSelectionCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-setselection";
	private static final String NEIGHBOUR_NAME = "gametest-setselection-neighbour";
	private static final String LABEL = BUILD_NAME + "/" + Build.MAIN;

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME, NEIGHBOUR_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 2x2x2 stone cube hovering one block above the ground in front of and to the right of the player.
			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(1, 1, 1);
			BuildBox box = new BuildBox(min, max);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			select(singleplayer, min, max);

			// Nothing selected yet.
			assertMessages(run(context, "vcs setSelection"), "Nothing selected in this world");

			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			read(schematic(BUILD_NAME, 1));
			assertBox(context, singleplayer, 1, box);

			// The WorldEdit selection the build was created from is its box already.
			assertMessages(run(context, "vcs setSelection"), "Placement " + LABEL + " already has your WorldEdit selection as its box; nothing to change");
			assertNoSchematic(BUILD_NAME, 2);

			// Without a WorldEdit selection there is nothing to take the box from.
			clearSelection(singleplayer);
			assertMessages(run(context, "vcs setSelection"), "No WorldEdit selection to give " + LABEL);
			assertNoSchematic(BUILD_NAME, 2);

			// A bigger selection, two blocks of air further east: the box takes it as it is, air and all, as v2.
			BuildBox bigger = new BuildBox(min, max.offset(2, 0, 0));
			select(singleplayer, bigger.min(), bigger.max());
			assertMessages(run(context, "vcs setSelection"), "Set the box of " + LABEL + " from 2x2x2 (8 blocks) to 4x2x2 (16 blocks) and committed it as v2");
			assertBox(context, singleplayer, 2, bigger);
			assertSize(read(schematic(BUILD_NAME, 2)), BlockVector3.at(4, 2, 2));

			lookAt(context, bigger.min(), bigger.max());
			screenshotLastFrame(context, "mcvcs-vcs-setselection");

			// A smaller one, only the cube's west half: the east half stays standing outside the box, which is warned
			// about twice, as blocks the build no longer has and as blocks touching its box.
			BuildBox half = new BuildBox(min, new BlockPos(min.getX(), max.getY(), max.getZ()));
			select(singleplayer, half.min(), half.max());
			assertMessages(run(context, "vcs setSelection"),
				"Set the box of " + LABEL + " from 4x2x2 (16 blocks) to 1x2x2 (4 blocks) and committed it as v3",
				"Warning: 4 blocks of the old box are outside the new one; they stay in the world but are no longer part of the build",
				"Warning: the build is not enclosed by air");
			assertBox(context, singleplayer, 3, half);
			assertSize(read(schematic(BUILD_NAME, 3)), BlockVector3.at(1, 2, 2));
			if (!singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(max).is(Blocks.STONE))) {
				throw new AssertionError("Expected the block left outside the box at " + max + " to stay standing");
			}

			// A second build a few blocks east; a selection reaching into it is refused and the box stays as it was.
			BlockPos neighbourMin = max.offset(4, -1, -1);
			BlockPos neighbourMax = neighbourMin.offset(1, 1, 1);
			fillBox(singleplayer, neighbourMin, neighbourMax, Blocks.STONE.defaultBlockState(), neighbourMin, Blocks.STONE.defaultBlockState());
			select(singleplayer, neighbourMin, neighbourMax);
			runCommand(context, "vcs create " + NEIGHBOUR_NAME + " -we");
			read(schematic(NEIGHBOUR_NAME, 1));
			runCommand(context, "vcs select " + BUILD_NAME);
			select(singleplayer, min, neighbourMin);
			assertMessages(run(context, "vcs setSelection"), "Setting the box of " + LABEL + " to 6x1x1 would overlap " + NEIGHBOUR_NAME + "/" + Build.MAIN + "; placements may not intersect");
			assertNoSchematic(BUILD_NAME, 4);
			assertBox(context, singleplayer, 3, half);
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/** Exactly one message per prefix, in order, each starting with its prefix. */
	private static void assertMessages(List<Component> messages, String... prefixes) {
		boolean matches = messages.size() == prefixes.length;
		for (int i = 0; matches && i < prefixes.length; i++) {
			matches = messages.get(i).getString().startsWith(prefixes[i]);
		}
		if (!matches) {
			throw new AssertionError("Expected messages starting with " + List.of(prefixes) + " but got " + messages.stream().map(Component::getString).toList());
		}
	}

	private static void assertNoSchematic(String name, int version) {
		if (Files.exists(schematic(name, version))) {
			throw new AssertionError("Expected no " + schematic(name, version));
		}
	}

	/** The build on disk and, once the server has told it, the client's copy both have the given version and box. */
	private static void assertBox(ClientGameTestContext context, TestSingleplayerContext singleplayer, int version, BuildBox box) {
		Optional<Build> build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, BUILD_NAME));
		if (build.isEmpty() || build.get().version() != version || !mainBox(build.get()).equals(box)) {
			throw new AssertionError("Expected build '" + BUILD_NAME + "' v" + version + " with box " + box + " but got " + build.orElse(null));
		}
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(BUILD_NAME) && selected.head() == version;
		});
		ClientPlacement shown = context.computeOnClient(client -> ClientPlacements.selected());
		if (!shown.box().equals(box)) {
			throw new AssertionError("Expected the client to show box " + box + " for '" + BUILD_NAME + "' but got " + shown.box());
		}
	}
}
