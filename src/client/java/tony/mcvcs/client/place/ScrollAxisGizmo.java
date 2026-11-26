package tony.mcvcs.client.place;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shows the axis the mouse wheel works along while the scroll key is held, for the {@code /vcs place} copy and the
 * {@code /vcs resize} box alike: the face it sticks out of is filled in, and the axis runs dashed inside the box and
 * solid outside it, with a {@code <} and a {@code >} on the outside part for the two ways the wheel goes. Everything
 * outside the box grows with the player's distance, so it stays as big on screen however far off they stand.
 */
public final class ScrollAxisGizmo {
	private static final float LINE_WIDTH = 2.0f;
	/** How opaque the face the axis sticks out of is filled. */
	private static final float FILL_ALPHA = 0.3f;
	/** The axis, in pixels. */
	private static final float AXIS_WIDTH = 3.0f;
	/** The chevrons on it: yellow, to stand out from the box, whatever its color, and the grass. */
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

	private ScrollAxisGizmo() {
	}

	/** The axis sticking out of the {@code side} face of {@code aabb}, the box and axis drawn in {@code color}. */
	public static void draw(AABB aabb, Direction side, int color) {
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
}
