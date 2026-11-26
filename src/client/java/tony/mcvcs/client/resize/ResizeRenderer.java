package tony.mcvcs.client.resize;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.resize.ClientResize.Resize;
import tony.mcvcs.client.resize.ClientResize.Target;

/**
 * Draws the box a {@code /vcs resize} is showing: orange while it can be committed, red while it would be refused,
 * with the face the wheel drags filled in and the placement's name and size above it. Like the {@code /vcs place}
 * copy's box it is drawn on top of everything, so it is found behind walls. A line on the HUD says how to drag and
 * commit it.
 */
public final class ResizeRenderer {
	/** Line color (ARGB) while the box can be committed: orange, apart from the cyan selection and green previews. */
	public static final int COLOR = 0xFFFFA940;
	/** Line color (ARGB) while it would be refused: the red the {@code /vcs place} copy turns. */
	public static final int REFUSED_COLOR = 0xFFFF6B6B;
	private static final float LINE_WIDTH = 2.0f;
	/** How opaque the dragged face is filled. */
	private static final float FILL_ALPHA = 0.3f;
	private static final float SCALE = 0.6f;
	private static final double GAP = 0.5;
	private static final double OUTSET = 0.01;
	/** How thick the slab that marks the dragged face is, in blocks. */
	private static final double FACE_THICKNESS = 0.02;
	/** Above the action bar, where the {@code /vcs place} hint goes too; the two are never shown together. */
	private static final int OFFSET_FROM_BOTTOM = 84;

	private ResizeRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(ResizeRenderer::emit);
		HudElementRegistry.attachElementBefore(VanillaHudElements.OVERLAY_MESSAGE, MCVCS.id("resize_hint"), ResizeRenderer::hint);
	}

	private static void emit(LevelExtractionContext context) {
		Resize resize = ClientResize.active();
		if (resize == null || !resize.dimension().equals(context.level().dimension())) {
			return;
		}

		String reason = ClientResize.reason();
		int color = reason == null ? COLOR : REFUSED_COLOR;
		AABB aabb = PlacePreviewKeys.aabb(resize.box()).inflate(OUTSET);
		Gizmos.cuboid(aabb, GizmoStyle.stroke(color, LINE_WIDTH)).setAlwaysOnTop();

		// The wheel only drags while the place preview is not taking it.
		Target target = PreviewManager.place() == null ? ClientResize.target(Minecraft.getInstance()) : null;
		if (target != null) {
			Gizmos.cuboid(face(aabb, target.face()), GizmoStyle.strokeAndFill(color, LINE_WIDTH, ARGB.multiplyAlpha(color, FILL_ALPHA))).setAlwaysOnTop();
		}

		BuildBox box = resize.box();
		String label = resize.label() + " " + box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + " (RESIZING)" + (reason == null ? "" : " - " + reason);
		double lift = GAP + Minecraft.getInstance().font.lineHeight * SCALE / 16.0;
		Vec3 center = aabb.getCenter();
		TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(color).withScale(SCALE);
		Gizmos.billboardText(label, new Vec3(center.x, aabb.maxY + lift, center.z), style).setAlwaysOnTop();
	}

	/** A thin slab lying on the {@code face} of {@code aabb}. */
	private static AABB face(AABB aabb, Direction face) {
		return switch (face) {
			case NORTH -> new AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.minZ + FACE_THICKNESS);
			case SOUTH -> new AABB(aabb.minX, aabb.minY, aabb.maxZ - FACE_THICKNESS, aabb.maxX, aabb.maxY, aabb.maxZ);
			case WEST -> new AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.minX + FACE_THICKNESS, aabb.maxY, aabb.maxZ);
			case EAST -> new AABB(aabb.maxX - FACE_THICKNESS, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
			case DOWN -> new AABB(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.minY + FACE_THICKNESS, aabb.maxZ);
			case UP -> new AABB(aabb.minX, aabb.maxY - FACE_THICKNESS, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
		};
	}

	/** E.g. {@code Resizing. Left Alt + Mouse Wheel to drag a face, Keypad 5 or /vcs confirmResize to commit}. */
	public static Component message() {
		return Component.literal("Resizing. ")
			.append(PlacePreviewKeys.SCROLL.getTranslatedKeyMessage())
			.append(" + Mouse Wheel to drag a face, ")
			.append(PlacePreviewKeys.CONFIRM.getTranslatedKeyMessage())
			.append(" or ")
			.append(Component.literal("/vcs confirmResize").withStyle(ChatFormatting.GREEN))
			.append(" to commit");
	}

	private static void hint(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (ClientResize.active() == null || PreviewManager.place() != null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		Component message = message();
		int width = font.width(message);
		graphics.textWithBackdrop(font, message, (graphics.guiWidth() - width) / 2, graphics.guiHeight() - OFFSET_FROM_BOTTOM, width, ARGB.white(255));
	}
}
