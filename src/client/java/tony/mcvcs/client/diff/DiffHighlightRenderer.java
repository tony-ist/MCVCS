package tony.mcvcs.client.diff;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;

import tony.mcvcs.diff.ChangeKind;

/**
 * Draws the highlighted diff as translucent colored boxes over the changed blocks: green where a block was added
 * since the compared version, red where one was removed, yellow where it was swapped for another.
 * <p>
 * Like the build labels, the boxes are drawn on top of everything: a diff is for finding what changed, and much of a
 * redstone build is under a floor or behind a wall. The boxes are pushed slightly outside the blocks they cover so
 * their faces do not z-fight with those of the blocks themselves.
 */
public final class DiffHighlightRenderer {
	/** Box color (ARGB) per {@link ChangeKind}; the fill is the same color at {@link #FILL_ALPHA}. */
	public static final int ADDED_COLOR = 0xFF4CE05A;
	public static final int REMOVED_COLOR = 0xFFF04A4A;
	public static final int CHANGED_COLOR = 0xFFF5D142;
	/** Opacity of the box faces; the outline is fully opaque. */
	public static final float FILL_ALPHA = 0.3f;
	private static final float LINE_WIDTH = 2.0f;
	private static final double OUTSET = 0.01;

	private static final GizmoStyle ADDED = style(ADDED_COLOR);
	private static final GizmoStyle REMOVED = style(REMOVED_COLOR);
	private static final GizmoStyle CHANGED = style(CHANGED_COLOR);

	private DiffHighlightRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(DiffHighlightRenderer::emit);
	}

	private static GizmoStyle style(int color) {
		return GizmoStyle.strokeAndFill(color, LINE_WIDTH, ARGB.multiplyAlpha(color, FILL_ALPHA));
	}

	/** The style boxes of {@code kind} are drawn in. */
	public static GizmoStyle style(ChangeKind kind) {
		return switch (kind) {
			case ADDED -> ADDED;
			case REMOVED -> REMOVED;
			case CHANGED -> CHANGED;
		};
	}

	private static void emit(LevelExtractionContext context) {
		ClientDiff diff = DiffManager.active();
		if (diff == null || !diff.dimension().equals(context.level().dimension())) {
			return;
		}

		for (DiffHighlights.Highlight highlight : diff.highlights()) {
			Gizmos.cuboid(highlight.aabb().inflate(OUTSET), style(highlight.kind())).setAlwaysOnTop();
		}
	}
}
