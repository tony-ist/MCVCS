package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertOrigin;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import tony.mcvcs.build.BuildStorage;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsCreateCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-build";
	private static final String OVERLAPPING_NAME = "gametest-overlap";
	private static final String ADJACENT_NAME = "gametest-adjacent";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// /vcs requires op, which in singleplayer means cheats must be on.
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME, OVERLAPPING_NAME, ADJACENT_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 3x2x2 box in front of the player: stone everywhere except one gold block at the corner the origin is anchored to.
			BlockPos playerPos = playerPos(singleplayer);
			BlockPos min = playerPos.offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			// Top north-west corner of the box, spelled out rather than taken from the command's own constants.
			BlockVector3 originCorner = BlockVector3.at(adapter.adapt(min).x(), adapter.adapt(max).y(), adapter.adapt(min).z());
			// The origin sits one block above the build, so a paste lands it below the player instead of around them.
			BlockVector3 expectedOrigin = originCorner.add(0, 1, 0);
			BlockPos gold = adapter.toBlockPos(originCorner);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);

			// The name is a folder name, so one that points outside the builds folder is refused before anything is written.
			runCommand(context, "vcs create ..");
			Path escaped = BuildStorage.root().resolve("..").resolve("v1.schem").normalize();
			if (Files.exists(escaped)) {
				throw new AssertionError("Create with name '..' must not write " + escaped);
			}
			// A name starting with a minus would read as a flag, so it is refused too.
			runCommand(context, "vcs create -dash -we");
			if (Files.exists(BuildStorage.root().resolve("-dash"))) {
				throw new AssertionError("Create with name '-dash' must not write a build folder");
			}

			runCommand(context, "vcs create " + BUILD_NAME + " -we");

			Clipboard clipboard = read(schematic(BUILD_NAME, 1));
			assertOrigin(clipboard, expectedOrigin);

			// A paste at position `to` puts a clipboard block at `to + (block - origin)`, so check where the build's top
			// layer ends up when pasted at the player's feet: one block below them, never inside them.
			BlockVector3 pasteAt = adapter.adapt(playerPos);
			int pastedTopY = pasteAt.add(originCorner.subtract(clipboard.getOrigin())).y();
			if (pastedTopY != pasteAt.y() - 1) {
				throw new AssertionError("Expected the top layer to paste at y " + (pasteAt.y() - 1) + " but it lands at y " + pastedTopY);
			}

			assertSize(clipboard, BlockVector3.at(3, 2, 2));
			assertBlock(clipboard, originCorner, BlockTypes.GOLD_BLOCK);

			// The diagonally opposite corner must be stone.
			BlockVector3 opposite = adapter.adapt(min).add(adapter.adapt(max)).subtract(originCorner);
			assertBlock(clipboard, opposite, BlockTypes.STONE);

			// Creating the build again, by its name or by the name in other letters, is refused and leaves v1 as it was;
			// a diamond put in the box beforehand shows that nothing was rewritten.
			setBlock(singleplayer, adapter.toBlockPos(opposite), Blocks.DIAMOND_BLOCK.defaultBlockState());
			List<Component> same = run(context, "vcs create " + BUILD_NAME);
			assertOnlyMessage(same, "Build " + BUILD_NAME + " already exists in this world; select it with /vcs select " + BUILD_NAME);
			List<Component> otherCase = run(context, "vcs create " + BUILD_NAME.toUpperCase(Locale.ROOT));
			assertOnlyMessage(otherCase, "Build " + BUILD_NAME + " already exists in this world");
			assertBlock(read(schematic(BUILD_NAME, 1)), opposite, BlockTypes.STONE);
			assertNoFolderOfItsOwn(BUILD_NAME.toUpperCase(Locale.ROOT), BUILD_NAME);
			setBlock(singleplayer, adapter.toBlockPos(opposite), Blocks.STONE.defaultBlockState());

			// A box that shares even one block with an existing build is refused, so nothing is written for it.
			select(singleplayer, max, max.offset(2, 2, 2));
			runCommand(context, "vcs create " + OVERLAPPING_NAME + " -we");
			if (Files.exists(BuildStorage.directory(OVERLAPPING_NAME))) {
				throw new AssertionError("Create must not write a build whose box overlaps '" + BUILD_NAME + "'");
			}

			// One that merely touches the existing build's face is fine.
			select(singleplayer, max.offset(1, 0, 0), max.offset(2, 2, 2));
			runCommand(context, "vcs create " + ADJACENT_NAME + " -we");
			read(schematic(ADJACENT_NAME, 1));

			context.takeScreenshot("mcvcs-vcs-create");
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

	/**
	 * No build folder was written for {@code name}. On a file system that ignores case, its path is the folder of
	 * {@code existing}, which is fine; anywhere else it must not be there at all.
	 */
	private static void assertNoFolderOfItsOwn(String name, String existing) {
		Path folder = BuildStorage.directory(name);
		try {
			if (Files.exists(folder) && !Files.isSameFile(folder, BuildStorage.directory(existing))) {
				throw new AssertionError("Create must not write " + folder + " next to " + BuildStorage.directory(existing));
			}
		} catch (IOException e) {
			throw new AssertionError("Failed to compare " + folder + " with the folder of '" + existing + "'", e);
		}
	}
}
