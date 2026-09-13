package tony.mcvcs.client.label;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import tony.mcvcs.client.project.ClientProjects;
import tony.mcvcs.client.selection.SelectionBoxRenderer;
import tony.mcvcs.project.ClientProject;
import tony.mcvcs.project.ProjectBox;

/**
 * Floats each build's name above its box while the player is near it, selected or not.
 * <p>
 * Labels are camera-facing text emitted through the gizmo pipeline once per frame, like the selection box, but drawn
 * on top of everything: a name half hidden behind a wall or a tree is unreadable, and seeing a label through the roof
 * is how you tell which build you are standing in. A label fades in over the last few blocks of its range instead of
 * popping into view, and the selected build's label takes the color of its box.
 */
public final class BuildLabelRenderer {
	/** How close the camera must be to a build's box, in blocks, for its label to show. */
	public static final double RANGE = 32.0;
	/** The label fades in over this many blocks at the far end of {@link #RANGE}. */
	public static final double FADE = 8.0;
	/** Text size in the units of {@link TextGizmo.Style}: 16 units to a block, so 9-pixel-tall text is 0.34 blocks. */
	private static final float SCALE = 0.6f;
	/** Gap between the top of the box and the bottom of the text, in blocks. */
	private static final double GAP = 0.5;
	/** Label color (ARGB) of every build but the selected one. */
	private static final int COLOR = 0xFFFFFFFF;

	private BuildLabelRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(BuildLabelRenderer::emit);
	}

	/**
	 * How opaque the label of a build covering {@code box} is when seen from {@code camera}: fully within
	 * {@link #RANGE} minus {@link #FADE} blocks of the box, not at all beyond {@link #RANGE}, fading in between.
	 */
	public static float opacity(ProjectBox box, Vec3 camera) {
		double distance = Math.sqrt(aabb(box).distanceToSqr(camera));
		return (float) Mth.clamp((RANGE - distance) / FADE, 0.0, 1.0);
	}

	private static void emit(LevelExtractionContext context) {
		List<ClientProject> projects = ClientProjects.all();
		if (projects.isEmpty()) {
			return;
		}

		ClientProject selected = ClientProjects.selected();
		ResourceKey<Level> dimension = context.level().dimension();
		Vec3 camera = context.camera().position();
		// The text hangs down from its position, so the position is lifted by the text's own height to keep the gap.
		double lift = GAP + Minecraft.getInstance().font.lineHeight * SCALE / 16.0;

		for (ClientProject project : projects) {
			if (!project.dimension().equals(dimension)) {
				continue;
			}
			float opacity = opacity(project.box(), camera);
			if (opacity <= 0.0f) {
				continue;
			}

			AABB aabb = aabb(project.box());
			Vec3 center = aabb.getCenter();
			Vec3 pos = new Vec3(center.x, aabb.maxY + lift, center.z);
			int color = ARGB.multiplyAlpha(project.equals(selected) ? SelectionBoxRenderer.COLOR : COLOR, opacity);
			Gizmos.billboardText(project.name(), pos, TextGizmo.Style.forColorAndCentered(color).withScale(SCALE)).setAlwaysOnTop();
		}
	}

	private static AABB aabb(ProjectBox box) {
		return AABB.encapsulatingFullBlocks(box.min(), box.max());
	}
}
