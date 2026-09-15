package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.putItem;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;
import static tony.mcvcs.gametest.VcsTestSupport.suggestions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.client.diff.ClientDiff;
import tony.mcvcs.client.diff.DiffHighlights;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.diff.BlockChange;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.diff.ChangeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * {@code /vcs diff} finds every block that differs between a saved version and the world, by state or by the data of
 * its block entity, tells them apart by whether they were added, removed or changed, and has the client highlight
 * them until {@code /vcs diff off}.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsDiffCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-diff";

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// v1: a 3x2x2 stone box in front of and to the right of the player, with a gold block in the top north-west
			// corner, the top north-east corner left empty and an empty barrel in the bottom north-west corner; all
			// three face the player.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			BlockPos gold = new BlockPos(min.getX(), max.getY(), min.getZ());
			BlockPos hole = new BlockPos(max.getX(), max.getY(), min.getZ());
			BlockPos middle = new BlockPos(min.getX() + 1, max.getY(), min.getZ());
			BlockPos barrel = new BlockPos(min.getX(), min.getY(), min.getZ());
			BuildBox box = new BuildBox(min, max);

			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), gold, Blocks.GOLD_BLOCK.defaultBlockState());
			setBlock(singleplayer, hole, Blocks.AIR.defaultBlockState());
			setBlock(singleplayer, barrel, Blocks.BARREL.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME);
			read(schematic(BUILD_NAME, 1));

			// Tab completion offers every version of the selected build alongside "off".
			assertSuggestions(singleplayer, "vcs diff ", List.of("1", "off"));

			// Nothing has changed since v1, so nothing is highlighted.
			runCommand(context, "vcs diff");
			assertNoDiff(context);

			// One block of each kind: the hole filled in, the gold block dug out, and a stone block swapped for diamond.
			// Plus a change that leaves the block itself alone: a diamond put into the barrel.
			setBlock(singleplayer, hole, Blocks.DIAMOND_BLOCK.defaultBlockState());
			setBlock(singleplayer, gold, Blocks.AIR.defaultBlockState());
			setBlock(singleplayer, middle, Blocks.DIAMOND_BLOCK.defaultBlockState());
			putItem(singleplayer, barrel, 0, new ItemStack(Items.DIAMOND));

			// Nothing to diff beyond the latest version; the earlier result stands.
			runCommand(context, "vcs diff 2");
			assertNoDiff(context);

			// With no version given the world is compared against the latest version, v1 for now.
			runCommand(context, "vcs diff");
			ClientDiff diff = waitForDiff(context, 1);
			assertDiff(diff, BUILD_NAME, 1, box);
			assertChanges(diff.diff(), Map.of(
				hole, new Expected(ChangeKind.ADDED, Blocks.AIR, Blocks.DIAMOND_BLOCK, false),
				gold, new Expected(ChangeKind.REMOVED, Blocks.GOLD_BLOCK, Blocks.AIR, false),
				middle, new Expected(ChangeKind.CHANGED, Blocks.STONE, Blocks.DIAMOND_BLOCK, false),
				barrel, new Expected(ChangeKind.CHANGED, Blocks.BARREL, Blocks.BARREL, true)
			));
			// The changed blocks are next to each other but differ in kind, so each gets a box of its own.
			assertHighlights(diff, List.of(
				new DiffHighlights.Highlight(blockBox(barrel), ChangeKind.CHANGED),
				new DiffHighlights.Highlight(blockBox(gold), ChangeKind.REMOVED),
				new DiffHighlights.Highlight(blockBox(middle), ChangeKind.CHANGED),
				new DiffHighlights.Highlight(blockBox(hole), ChangeKind.ADDED)
			));

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-diff");

			// v2 is the world as it is now, so diffing against it finds nothing and drops the highlights: the barrel's
			// contents read back from the schematic the same as from the world. Diffing against v1 explicitly finds
			// the same four blocks again.
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
			assertSuggestions(singleplayer, "vcs diff ", List.of("1", "2", "off"));

			runCommand(context, "vcs diff");
			assertNoDiff(context);
			runCommand(context, "vcs diff 1");
			diff = waitForDiff(context, 1);
			assertDiff(diff, BUILD_NAME, 1, box);
			if (diff.diff().size() != 4) {
				throw new AssertionError("Expected 4 changes against v1 but got " + diff.diff().changes());
			}

			// Taking the diamond out again makes the barrel match v1 but not v2.
			putItem(singleplayer, barrel, 0, ItemStack.EMPTY);
			runCommand(context, "vcs diff");
			diff = waitForDiff(context, 2);
			assertChanges(diff.diff(), Map.of(barrel, new Expected(ChangeKind.CHANGED, Blocks.BARREL, Blocks.BARREL, true)));

			runCommand(context, "vcs diff off");
			assertNoDiff(context);
			screenshotLastFrame(context, "mcvcs-vcs-diff-off");

			// Neighbouring blocks changed the same way merge into one box; unchanged blocks between them keep them apart.
			assertMerging(box);
		}
	}

	private record Expected(ChangeKind kind, Block from, Block to, boolean dataChanged) {
	}

	private static ClientDiff waitForDiff(ClientGameTestContext context, int version) {
		context.waitFor(client -> {
			ClientDiff diff = DiffManager.active();
			return diff != null && diff.version() == version;
		});
		return context.computeOnClient(client -> DiffManager.active());
	}

	private static void assertSuggestions(TestSingleplayerContext singleplayer, String command, List<String> expected) {
		List<String> actual = suggestions(singleplayer, command);
		if (!new HashSet<>(actual).equals(new HashSet<>(expected))) {
			throw new AssertionError("Expected /" + command + " to suggest " + expected + " but got " + actual);
		}
	}

	private static void assertNoDiff(ClientGameTestContext context) {
		context.waitTicks(5);
		ClientDiff diff = context.computeOnClient(client -> DiffManager.active());
		if (diff != null) {
			throw new AssertionError("Expected no diff but '" + diff.name() + "' v" + diff.version() + " is highlighted with " + diff.diff().changes());
		}
	}

	private static void assertDiff(ClientDiff diff, String name, int version, BuildBox box) {
		if (!diff.name().equals(name) || diff.version() != version) {
			throw new AssertionError("Expected diff of '" + name + "' v" + version + " but got '" + diff.name() + "' v" + diff.version());
		}
		if (!diff.dimension().equals(Level.OVERWORLD)) {
			throw new AssertionError("Expected diff in the overworld but got " + diff.dimension());
		}
		if (!diff.diff().box().equals(box)) {
			throw new AssertionError("Expected diff box " + box + " but got " + diff.diff().box());
		}
	}

	/** The diff holds exactly {@code expected}: one change per position, each of the given kind and states. */
	private static void assertChanges(BuildDiff diff, Map<BlockPos, Expected> expected) {
		if (diff.size() != expected.size()) {
			throw new AssertionError("Expected " + expected.size() + " changes at " + expected.keySet() + " but got " + diff.changes());
		}
		for (BlockChange change : diff.changes()) {
			Expected want = expected.get(change.pos());
			if (want == null) {
				throw new AssertionError("Unexpected change at " + change.pos() + ": " + change);
			}
			if (change.kind() != want.kind() || change.from().getBlock() != want.from() || change.to().getBlock() != want.to() || change.dataChanged() != want.dataChanged()) {
				throw new AssertionError("Expected " + want + " at " + change.pos() + " but got " + change);
			}
		}
		Map<ChangeKind, Integer> counts = diff.counts();
		for (ChangeKind kind : ChangeKind.values()) {
			long want = expected.values().stream().filter(e -> e.kind() == kind).count();
			if (counts.get(kind) != want) {
				throw new AssertionError("Expected " + want + " " + kind + " changes but counted " + counts.get(kind));
			}
		}
	}

	private static void assertHighlights(ClientDiff diff, List<DiffHighlights.Highlight> expected) {
		if (!diff.highlights().equals(expected)) {
			throw new AssertionError("Expected highlights " + expected + " but got " + diff.highlights());
		}
	}

	/**
	 * Without the world: a full row of changes of one kind along z merges into one box, one of a different kind
	 * next to it stays separate, and a single block cut off by an unchanged one gets its own box.
	 */
	private static void assertMerging(BuildBox box) {
		BlockPos min = box.min();
		BlockPos max = box.max();
		BuildDiff diff = new BuildDiff(box, List.of(
			// Top layer, west column: two blocks along z, both added.
			new BlockChange(new BlockPos(min.getX(), max.getY(), min.getZ()), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()),
			new BlockChange(new BlockPos(min.getX(), max.getY(), max.getZ()), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()),
			// Bottom layer, same column: also added, so the column merges into one 1x2x2 box.
			new BlockChange(new BlockPos(min.getX(), min.getY(), min.getZ()), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()),
			new BlockChange(new BlockPos(min.getX(), min.getY(), max.getZ()), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()),
			// Middle column, top: removed, so it cannot join the added ones.
			new BlockChange(new BlockPos(min.getX() + 1, max.getY(), min.getZ()), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()),
			// East column, top, far z: added but separated from the west column by the middle one.
			new BlockChange(new BlockPos(max.getX(), max.getY(), max.getZ()), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState())
		));
		List<DiffHighlights.Highlight> expected = List.of(
			new DiffHighlights.Highlight(new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + 1, max.getY() + 1, max.getZ() + 1), ChangeKind.ADDED),
			new DiffHighlights.Highlight(blockBox(new BlockPos(min.getX() + 1, max.getY(), min.getZ())), ChangeKind.REMOVED),
			new DiffHighlights.Highlight(blockBox(new BlockPos(max.getX(), max.getY(), max.getZ())), ChangeKind.ADDED)
		);
		List<DiffHighlights.Highlight> actual = DiffHighlights.merge(diff);
		if (!actual.equals(expected)) {
			throw new AssertionError("Expected merged highlights " + expected + " but got " + actual);
		}
	}

	private static AABB blockBox(BlockPos pos) {
		return new AABB(pos);
	}
}
