package tony.mcvcs.diff;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.block.state.BlockState;

import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.BuildBox;

/**
 * Which blocks differ between two {@link BoxSnapshot}s of the same box, and how. The old side is {@code from}, the
 * new side {@code to}: a block that is air in {@code from} but not in {@code to} was {@linkplain ChangeKind#ADDED
 * added}, and so on. Comparing a saved version against the world says what has been built since that version.
 * <p>
 * The diff is pure data on both sides of the connection: the server computes it and streams it to the client, which
 * highlights it, see {@link tony.mcvcs.network.DiffSender}. Immutable.
 *
 * @param box     the box both snapshots cover
 * @param changes every block that differs, in {@link BuildBox} order
 */
public record BuildDiff(BuildBox box, List<BlockChange> changes) {
	public BuildDiff {
		changes = List.copyOf(changes);
	}

	/**
	 * Every block that is not the same state in both snapshots.
	 *
	 * @throws IllegalArgumentException if the snapshots do not cover the same box
	 */
	public static BuildDiff between(BoxSnapshot from, BoxSnapshot to) {
		BuildBox box = from.box();
		if (!box.equals(to.box())) {
			throw new IllegalArgumentException("Cannot diff " + from.box() + " against " + to.box());
		}

		List<BlockChange> changes = new ArrayList<>();
		for (int index = 0; index < from.size(); index++) {
			BlockState before = from.state(index);
			BlockState after = to.state(index);
			// States are singletons, so identity is state equality.
			if (before != after) {
				changes.add(new BlockChange(box.pos(index), before, after));
			}
		}
		return new BuildDiff(box, changes);
	}

	/** A diff with no changes. */
	public static BuildDiff empty(BuildBox box) {
		return new BuildDiff(box, List.of());
	}

	public boolean isEmpty() {
		return changes.isEmpty();
	}

	/** Number of blocks that differ. */
	public int size() {
		return changes.size();
	}

	/** How many changes there are of each kind; kinds with none are present with a count of zero. */
	public Map<ChangeKind, Integer> counts() {
		Map<ChangeKind, Integer> counts = new EnumMap<>(ChangeKind.class);
		for (ChangeKind kind : ChangeKind.values()) {
			counts.put(kind, 0);
		}
		for (BlockChange change : changes) {
			counts.merge(change.kind(), 1, Integer::sum);
		}
		return counts;
	}
}
