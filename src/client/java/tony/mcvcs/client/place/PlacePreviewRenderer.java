package tony.mcvcs.client.place;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import tony.mcvcs.client.place.PlacePreviewStatus.Status;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;
import org.jspecify.annotations.Nullable;

/**
 * Draws the box of the copy a {@code /vcs place} is showing, with its name and version above it, so the player can
 * see where it stands while they move it about.
 * <p>
 * The outline is green while {@code /vcs confirmPlace} would put the copy down and red while it would be refused,
 * see {@link PlacePreviewStatus}. Unlike the selected placement's box, which is depth-tested against the world, this
 * one is drawn on top of everything: a copy being lined up behind a wall or inside a hillside still has to be found,
 * and its blocks are not really there to hide it.
 */
public final class PlacePreviewRenderer {
	/** Line color (ARGB) while the copy can be placed where it stands: the green of a preview. */
	public static final int COLOR = 0xFF7CFF7C;
	/** Line color (ARGB) while it would be refused: a light red, the same one {@code /vcs diff} marks removals with. */
	public static final int REFUSED_COLOR = 0xFFFF6B6B;
	/** {@link #COLOR} at a line width of 2 pixels. */
	private static final GizmoStyle STYLE = GizmoStyle.stroke(COLOR, 2.0f);
	/** {@link #REFUSED_COLOR} at a line width of 2 pixels. */
	private static final GizmoStyle REFUSED_STYLE = GizmoStyle.stroke(REFUSED_COLOR, 2.0f);
	/** Text size in the units of {@link TextGizmo.Style}: the same as the placement labels use. */
	private static final float SCALE = 0.6f;
	/** Gap between the top of the box and the bottom of the text, in blocks. */
	private static final double GAP = 0.5;
	/** Pushed slightly outside the blocks so the lines do not z-fight with faces on the box's surface. */
	private static final double OUTSET = 0.01;

	private PlacePreviewRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(PlacePreviewRenderer::emit);
	}

	private static void emit(LevelExtractionContext context) {
		PlacePreview preview = PreviewManager.place();
		if (preview == null || !preview.blocks().dimension().equals(context.level().dimension())) {
			return;
		}

		Status status = PlacePreviewStatus.status();
		// A status worked out for another box is one move old, so the box speaks for itself until the next tick.
		boolean placeable = status == null || !status.box().equals(preview.box()) || status.placeable();
		AABB aabb = PlacePreviewKeys.aabb(preview.box());
		Gizmos.cuboid(aabb.inflate(OUTSET), placeable ? STYLE : REFUSED_STYLE).setAlwaysOnTop();

		// The text hangs down from its position, so the position is lifted by the text's own height to keep the gap.
		double lift = GAP + Minecraft.getInstance().font.lineHeight * SCALE / 16.0;
		Vec3 center = aabb.getCenter();
		TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(placeable ? COLOR : REFUSED_COLOR).withScale(SCALE);
		Gizmos.billboardText(label(preview, status, placeable), new Vec3(center.x, aabb.maxY + lift, center.z), style).setAlwaysOnTop();
	}

	/** What floats above the copy: what it would be called and at which version, and why it cannot be placed. */
	private static String label(PlacePreview preview, @Nullable Status status, boolean placeable) {
		String label = preview.blocks().name() + " v" + preview.blocks().version() + " (PLACING)";
		String reason = placeable || status == null ? null : status.reason();
		return reason == null ? label : label + " - " + reason;
	}
}
