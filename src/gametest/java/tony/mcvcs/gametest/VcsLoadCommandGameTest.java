package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.assertBlock;
import static tony.mcvcs.gametest.VcsTestSupport.assertOrigin;
import static tony.mcvcs.gametest.VcsTestSupport.assertSize;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;
import static tony.mcvcs.gametest.VcsTestSupport.suggestions;

import java.util.HashSet;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

@SuppressWarnings("UnstableApiUsage")
public class VcsLoadCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-load";

	@Override
	protected void run(ClientGameTestContext context) {
		FabricAdapter adapter = FabricAdapter.get();

		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// v1: a 3x2x2 stone box in front of and to the right of the player, with a gold block in the top north-west
			// corner (the origin corner) and the top north-east corner left empty.
			BlockPos player = playerPos(singleplayer);
			BlockPos min = player.offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BlockPos gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			BlockPos hole = new BlockPos(max.getX(), max.getY(), min.getZ());
			BlockVector3 expectedOrigin = adapter.adapt(gold).add(0, 1, 0);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, hole, Blocks.AIR.defaultBlockState());
			select(singleplayer, min, max);

			// Nothing to load before a build has been created; the clipboard stays empty.
			runCommand(context, "vcs load");
			assertNoClipboard(singleplayer);

			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));

			// v2: the whole box rebuilt out of diamond, hole included, so v1 and v2 differ in every block.
			fillBox(singleplayer, min, max, Blocks.DIAMOND_BLOCK.defaultBlockState(), gold, Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));

			// Tab completion offers every version of the selected build.
			assertSuggestions(singleplayer, "vcs load ", List.of("-h", "1", "2"));

			// Nothing to load beyond the latest version; creating and committing did not touch the clipboard either.
			runCommand(context, "vcs load 3");
			assertNoClipboard(singleplayer);

			// Loading v1 puts the schematic as saved, origin included, into the player's WorldEdit clipboard.
			runCommand(context, "vcs load 1");
			Clipboard v1 = clipboard(singleplayer);
			assertOrigin(v1, expectedOrigin);
			assertSize(v1, BlockVector3.at(3, 2, 2));
			assertBlock(v1, adapter.adapt(gold), BlockTypes.GOLD_BLOCK);
			assertBlock(v1, adapter.adapt(min), BlockTypes.STONE);
			assertBlock(v1, adapter.adapt(hole), BlockTypes.AIR);
			// Only the clipboard changed; the world still holds v2 and nothing was written to WorldEdit's schematics.
			assertWorldBlock(singleplayer, gold, Blocks.DIAMOND_BLOCK);
			assertWorldBlock(singleplayer, hole, Blocks.DIAMOND_BLOCK);

			// //paste lines the origin up with the player's feet, so the origin corner lands directly under them and
			// the box extends east, south and down from there, one block below where the player stands.
			runCommand(context, "/paste");
			BlockPos pastedGold = player.offset(0, -1, 0);
			BlockPos pastedHole = pastedGold.offset(hole.getX() - gold.getX(), 0, 0);
			BlockPos pastedMin = pastedGold.offset(0, -(max.getY() - min.getY()), 0);
			assertWorldBlock(singleplayer, pastedGold, Blocks.GOLD_BLOCK);
			assertWorldBlock(singleplayer, pastedMin, Blocks.STONE);
			assertWorldBlock(singleplayer, pastedHole, Blocks.AIR);
			// The build's own box is untouched by the paste.
			assertWorldBlock(singleplayer, gold, Blocks.DIAMOND_BLOCK);

			lookAt(context, pastedMin, pastedHole);
			singleplayer.getClientLevel().waitForChunksRender();
			context.takeScreenshot("mcvcs-vcs-load-paste");

			// Loading again replaces the clipboard; with no version given it is the latest one.
			runCommand(context, "vcs load");
			Clipboard v2 = clipboard(singleplayer);
			assertOrigin(v2, expectedOrigin);
			assertBlock(v2, adapter.adapt(gold), BlockTypes.DIAMOND_BLOCK);
			assertBlock(v2, adapter.adapt(hole), BlockTypes.DIAMOND_BLOCK);
		}
	}

	/** What the player's WorldEdit session holds as its clipboard, the way {@code //paste} would see it. */
	private static Clipboard clipboard(TestSingleplayerContext singleplayer) {
		return singleplayer.getServer().computeOnServer(server -> {
			try {
				return session(server.getPlayerList().getPlayers().get(0)).getClipboard().getClipboard();
			} catch (EmptyClipboardException e) {
				throw new AssertionError("Expected /vcs load to fill the clipboard but it is empty", e);
			}
		});
	}

	private static void assertNoClipboard(TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			try {
				Clipboard clipboard = session(server.getPlayerList().getPlayers().get(0)).getClipboard().getClipboard();
				throw new AssertionError("Expected an empty clipboard but it holds " + clipboard.getDimensions() + " blocks");
			} catch (EmptyClipboardException expected) {
				// Nothing loaded, as it should be.
			}
		});
	}

	private static LocalSession session(ServerPlayer player) {
		return WorldEdit.getInstance().getSessionManager().get(FabricAdapter.get().fromNativePlayer(player));
	}

	private static void assertSuggestions(TestSingleplayerContext singleplayer, String command, List<String> expected) {
		List<String> actual = suggestions(singleplayer, command);
		if (!new HashSet<>(actual).equals(new HashSet<>(expected))) {
			throw new AssertionError("Expected /" + command + " to suggest " + expected + " but got " + actual);
		}
	}

	private static void assertWorldBlock(TestSingleplayerContext singleplayer, BlockPos pos, Block expected) {
		Block actual = singleplayer.getServer().computeOnServer(server -> server.overworld().getBlockState(pos).getBlock());
		if (actual != expected) {
			throw new AssertionError("Expected the world to hold " + expected + " at " + pos + " but it holds " + actual);
		}
	}
}
