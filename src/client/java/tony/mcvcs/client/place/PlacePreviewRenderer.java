package tony.mcvcs.client.place;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import tony.mcvcs.client.place.PlacePreviewKeys.ScrollAxis;
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
 * <p>
 * While the scroll key is held, the axis the wheel moves the copy along is drawn too, see
 * {@link PlacePreviewKeys.ScrollAxis}.
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
	private static final float LINE_WIDTH = 2.0f;
	/** How opaque the face the wheel's axis sticks out of is filled. */
	private static final float FILL_ALPHA = 0.3f;
	/** The wheel's axis, in pixels. */
	private static final float AXIS_WIDTH = 3.0f;
	/** The chevrons on it: yellow, to stand out from the green or red box and the grass. */
	private static final int CHEVRON_COLOR = 0xFFFFE14D;
	private static final float CHEVRON_WIDTH = 4.0f;
	/*
	 * The sizes below are in blocks for a player standing up to SCALE_DISTANCE blocks off; farther away they grow
	 * with the distance.
	 */
	private static final double SCALE_DISTANCE = 12.0;
	/** How far the axis reaches out of the box. */
	private static final double OUTSIDE = 3.0;
	/** How far into the box it is drawn, dashed, and how long each dash and gap is. */
	private static final double DASHED = 1.0;
	private static final double DASH = 0.2;
	/** Where the tips of the chevron pointing into the box and the one pointing out sit, out from the face. */
	private static final double INWARD_AT = 1.0;
	private static final double OUTWARD_AT = 2.6;
	/** How far a chevron's arms reach back and out from its tip. */
	private static final double CHEVRON = 0.4;

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

		ScrollAxis axis = PlacePreviewKeys.scrollAxis();
		if (axis != null) {
			axis(aabb, axis, placeable ? COLOR : REFUSED_COLOR);
		}
	}

	/**
	 * The axis the wheel moves the copy along, sticking out of the face it was picked by (or, picked by the player's
	 * view, the face towards them): dashed inside the box, solid outside it, with a {@code <} and a {@code >} on the
	 * outside part for the two ways the wheel moves the copy. The face is filled in. Everything outside the box grows
	 * with the player's distance, so it stays as big on screen however far off they stand.
	 */
	private static void axis(AABB aabb, ScrollAxis axis, int color) {
		// The face looked at is the one facing the player, which is the way the copy comes back towards them.
		Direction side = axis.away().getOpposite();
		Gizmos.rect(new Vec3(aabb.minX, aabb.minY, aabb.minZ), new Vec3(aabb.maxX, aabb.maxY, aabb.maxZ), side,
			GizmoStyle.strokeAndFill(color, LINE_WIDTH, ARGB.multiplyAlpha(color, FILL_ALPHA))).setAlwaysOnTop();

		Vec3 out = side.getUnitVec3();
		double half = (aabb.max(side.getAxis()) - aabb.min(side.getAxis())) / 2.0;
		Vec3 face = aabb.getCenter().add(out.scale(half));
		Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		double scale = Math.max(1.0, eye.distanceTo(face) / SCALE_DISTANCE);

		// Dashed inside the box, a little way in from the face.
		double inside = Math.min(half, DASHED * scale);
		double dash = DASH * scale;
		for (double from = 0.0; from < inside; from += 2.0 * dash) {
			Gizmos.line(face.subtract(out.scale(from)), face.subtract(out.scale(Math.min(from + dash, inside))), color, AXIS_WIDTH).setAlwaysOnTop();
		}
		Gizmos.line(face, face.add(out.scale(OUTSIDE * scale)), color, AXIS_WIDTH).setAlwaysOnTop();

		// < points back into the box, which is a notch up; > points out of it, a notch down.
		chevron(face.add(out.scale(INWARD_AT * scale)), out.reverse(), eye, scale);
		chevron(face.add(out.scale(OUTWARD_AT * scale)), out, eye, scale);
	}

	/**
	 * A chevron with its tip at {@code tip}, pointing {@code direction}, its arms spread across the axis the way the
	 * player sees most of them.
	 */
	private static void chevron(Vec3 tip, Vec3 direction, Vec3 eye, double scale) {
		Vec3 across = direction.cross(eye.subtract(tip));
		if (across.lengthSqr() < 1.0e-6) {
			// Looking straight down the axis, any way across it will do.
			across = direction.cross(Math.abs(direction.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0));
		}
		across = across.normalize().scale(CHEVRON * scale);
		Vec3 back = tip.subtract(direction.scale(CHEVRON * scale));
		Gizmos.line(tip, back.add(across), CHEVRON_COLOR, CHEVRON_WIDTH).setAlwaysOnTop();
		Gizmos.line(tip, back.subtract(across), CHEVRON_COLOR, CHEVRON_WIDTH).setAlwaysOnTop();
	}

	/** What floats above the copy: what it would be called and at which version, and why it cannot be placed. */
	private static String label(PlacePreview preview, @Nullable Status status, boolean placeable) {
		String label = preview.blocks().name() + " v" + preview.blocks().version() + " (PLACING)";
		String reason = placeable || status == null ? null : status.reason();
		return reason == null ? label : label + " - " + reason;
	}
}
