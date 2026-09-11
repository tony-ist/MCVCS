package tony.mcvcs.client.selection;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import tony.mcvcs.network.SelectedProjectPayload;
import tony.mcvcs.project.ProjectBox;
import tony.mcvcs.project.SelectedProject;
import org.jspecify.annotations.Nullable;

/**
 * Draws the bounding box of the player's selected project as a thin line outline.
 * <p>
 * The outline is emitted through the game's own gizmo pipeline once per frame, so it is depth-tested like any block:
 * clearly visible when the project is in view, never painted over terrain or entities standing in front of it, and
 * gone when the player looks away or moves behind a wall.
 */
public final class SelectionBoxRenderer {
	/** Line color (ARGB) and width in pixels: a light cyan that stands out against both terrain and sky. */
	private static final GizmoStyle STYLE = GizmoStyle.stroke(0xFF4FD8FF, 2.0f);
	/** Pushed slightly outside the blocks so the lines do not z-fight with faces on the box's surface. */
	private static final double OUTSET = 0.01;

	private static volatile @Nullable SelectedProject selected;

	private SelectionBoxRenderer() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SelectedProjectPayload.TYPE, (payload, context) -> selected = payload.selected().orElse(null));
		// The server resends the selection for the new world on every level change, so drop the old one meanwhile.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> selected = null);
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> selected = null);
		LevelRenderEvents.END_EXTRACTION.register(SelectionBoxRenderer::emit);
	}

	/** The project whose box is drawn, if any. */
	public static @Nullable SelectedProject selected() {
		return selected;
	}

	private static void emit(LevelExtractionContext context) {
		SelectedProject project = selected;
		if (project == null || !project.dimension().equals(context.level().dimension())) {
			return;
		}

		ProjectBox box = project.box();
		AABB aabb = AABB.encapsulatingFullBlocks(box.min(), box.max()).inflate(OUTSET);
		Gizmos.cuboid(aabb, STYLE);
	}
}
