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

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import tony.mcvcs.command.VcsCommand;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs commit} saves the box captured at create time as the next version, whatever the WorldEdit selection
 * is now, and warns in yellow, without refusing, when blocks outside the box touch it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsCommitCommandGameTest implements FabricClientGameTest {
	private static final String BUILD_NAME = "gametest-commit";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// Same 3x2x2 box as the create test, stone with a gold block at the top north-west corner, but hovering one
			// block above the ground so that nothing touches it.
			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(2, 1, 1);
			BlockVector3 originCorner = BlockVector3.at(adapter.adapt(min).x(), adapter.adapt(max).y(), adapter.adapt(min).z());
			BlockVector3 expectedOrigin = originCorner.add(0, 1, 0);
			BlockVector3 opposite = adapter.adapt(min).add(adapter.adapt(max)).subtract(originCorner);
			BlockPos gold = adapter.toBlockPos(originCorner);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);

			// Nothing to commit before a build has been created.
			runCommand(context, "vcs commit");
			if (Files.exists(schematic(BUILD_NAME, 2))) {
				throw new AssertionError("Commit without a selected build must not write " + schematic(BUILD_NAME, 2));
			}

			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));

			// Move the WorldEdit selection well away from the box and edit the box itself. Commit must follow the
			// region captured at create time, so it sees the edit and ignores the new selection.
			select(singleplayer, min.offset(10, 0, 10), max.offset(10, 0, 10));
			setBlock(singleplayer, adapter.toBlockPos(opposite), Blocks.DIAMOND_BLOCK.defaultBlockState());

			List<Component> committed = run(context, "vcs commit");
			Clipboard v2 = read(schematic(BUILD_NAME, 2));
			assertOrigin(v2, expectedOrigin);
			assertSize(v2, BlockVector3.at(3, 2, 2));
			assertBlock(v2, originCorner, BlockTypes.GOLD_BLOCK);
			assertBlock(v2, opposite, BlockTypes.DIAMOND_BLOCK);
			// Nothing touches the box, so the commit message is all there is.
			assertMessages(committed, List.of("Committed build '" + BUILD_NAME + "' v2"));

			// Each further commit bumps the version. A block touching the box only at a corner is outside it, so the
			// version is written without it and a yellow warning says so.
			BlockPos outside = max.offset(1, 1, 1);
			setBlock(singleplayer, outside, Blocks.GOLD_BLOCK.defaultBlockState());
			List<Component> warned = run(context, "vcs commit");
			Clipboard v3 = read(schematic(BUILD_NAME, 3));
			assertOrigin(v3, expectedOrigin);
			assertSize(v3, BlockVector3.at(3, 2, 2));
			assertBlock(v3, opposite, BlockTypes.DIAMOND_BLOCK);
			assertMessages(warned, List.of("Committed build '" + BUILD_NAME + "' v3", VcsCommand.NOT_ENCLOSED_WARNING));
			if (!TextColor.fromLegacyFormat(ChatFormatting.YELLOW).equals(warned.get(1).getStyle().getColor())) {
				throw new AssertionError("Expected the warning to be yellow but its style is " + warned.get(1).getStyle());
			}

			context.takeScreenshot("mcvcs-vcs-commit");
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/** The messages are exactly {@code prefixes} long and each starts with its prefix. */
	private static void assertMessages(List<Component> messages, List<String> prefixes) {
		List<String> texts = messages.stream().map(Component::getString).toList();
		if (texts.size() != prefixes.size()) {
			throw new AssertionError("Expected messages starting with " + prefixes + " but got " + texts);
		}
		for (int i = 0; i < prefixes.size(); i++) {
			if (!texts.get(i).startsWith(prefixes.get(i))) {
				throw new AssertionError("Expected message " + i + " to start with '" + prefixes.get(i) + "' but got " + texts);
			}
		}
	}
}
