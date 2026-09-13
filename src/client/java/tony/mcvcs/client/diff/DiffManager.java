package tony.mcvcs.client.diff;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import tony.mcvcs.MCVCS;
import tony.mcvcs.diff.BlockChange;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.network.DiffBeginPayload;
import tony.mcvcs.network.DiffChangesPayload;
import tony.mcvcs.network.DiffClearPayload;
import org.jspecify.annotations.Nullable;

/**
 * Client side of the diff protocol. Holds the diff the server last sent so {@link DiffHighlightRenderer} can draw
 * it. Nothing in the world or its rendering is touched; the highlights are gizmos emitted each frame.
 * <p>
 * Packets and events are handled on the client thread; the render thread only ever reads the immutable
 * {@link ClientDiff} published through {@link #active}.
 */
public final class DiffManager {
	/** The diff currently highlighted, or null for none. */
	private static volatile @Nullable ClientDiff active;
	/** The diff whose slices are still arriving; null between diffs. */
	private static @Nullable Pending pending;

	private DiffManager() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(DiffBeginPayload.TYPE, (payload, context) -> begin(payload));
		ClientPlayNetworking.registerGlobalReceiver(DiffChangesPayload.TYPE, (payload, context) -> changes(payload));
		ClientPlayNetworking.registerGlobalReceiver(DiffClearPayload.TYPE, (payload, context) -> clear());
		// A diff belongs to the world it was requested in, so leaving that world drops it.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> clear());
	}

	/** The diff currently highlighted, if any. */
	public static @Nullable ClientDiff active() {
		return active;
	}

	private static void begin(DiffBeginPayload payload) {
		Pending target = new Pending(payload);
		if (target.complete()) {
			pending = null;
			active = target.build();
		} else {
			pending = target;
		}
	}

	private static void changes(DiffChangesPayload payload) {
		Pending target = pending;
		if (target == null) {
			MCVCS.LOGGER.warn("Ignoring diff changes that arrived without a diff begin");
			return;
		}

		try {
			target.accept(payload);
		} catch (IndexOutOfBoundsException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Dropping diff of '{}' v{}: malformed slice", target.begin.name(), target.begin.version(), e);
			pending = null;
			return;
		}

		if (target.complete()) {
			pending = null;
			active = target.build();
		}
	}

	private static void clear() {
		pending = null;
		active = null;
	}

	/** A diff being assembled from its slices. */
	private static final class Pending {
		private final DiffBeginPayload begin;
		private final List<BlockChange> changes;

		Pending(DiffBeginPayload begin) {
			this.begin = begin;
			this.changes = new ArrayList<>(begin.total());
		}

		void accept(DiffChangesPayload slice) {
			if (changes.size() + slice.size() > begin.total()) {
				throw new IllegalArgumentException("Slice of " + slice.size() + " changes exceeds the " + begin.total() + " announced");
			}
			changes.addAll(slice.changes(begin.box()));
		}

		boolean complete() {
			return changes.size() == begin.total();
		}

		ClientDiff build() {
			return new ClientDiff(begin.name(), begin.version(), begin.dimension(), new BuildDiff(begin.box(), changes));
		}
	}
}
