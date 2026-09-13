package tony.mcvcs.client.selection;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.build.ClientBuild;
import tony.mcvcs.build.BuildBox;

/**
 * Draws the bounding box of the player's selected build as a thin line outline.
 * <p>
 * The outline is emitted through the game's own gizmo pipeline once per frame, so it is depth-tested like any block:
 * clearly visible when the build is in view, never painted over terrain or entities standing in front of it, and
 * gone when the player looks away or moves behind a wall.
 */
public final class SelectionBoxRenderer {
	/** Line color (ARGB): a light cyan that stands out against both terrain and sky; the selected build's label shares it. */
	public static final int COLOR = 0xFF4FD8FF;
	/** {@link #COLOR} at a line width of 2 pixels. */
	private static final GizmoStyle STYLE = GizmoStyle.stroke(COLOR, 2.0f);
	/** Pushed slightly outside the blocks so the lines do not z-fight with faces on the box's surface. */
	private static final double OUTSET = 0.01;

	private SelectionBoxRenderer() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(SelectionBoxRenderer::emit);
	}

	private static void emit(LevelExtractionContext context) {
		ClientBuild build = ClientBuilds.selected();
		if (build == null || !build.dimension().equals(context.level().dimension())) {
			return;
		}

		BuildBox box = build.box();
		AABB aabb = AABB.encapsulatingFullBlocks(box.min(), box.max()).inflate(OUTSET);
		Gizmos.cuboid(aabb, STYLE);
	}
}
