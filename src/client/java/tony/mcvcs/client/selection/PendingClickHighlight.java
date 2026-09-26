package tony.mcvcs.client.selection;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import tony.mcvcs.network.PendingClickPayload;

/**
 * While a {@code /vcs create} or {@code /vcs select} waits for the player to click a block, outlines the block under
 * the crosshair with a thick line, so the player can see which block their punch would hand over. The server says when
 * a click starts and stops being waited for, see {@link PendingClickPayload}.
 */
public final class PendingClickHighlight {
	/** Line color (ARGB): a warm yellow, apart from the cyan selection box and the green preview. */
	public static final int COLOR = 0xFFFFD84F;
	/** {@link #COLOR} at a line width of 4 pixels, twice the selection box's, so it reads as a tool being held. */
	private static final GizmoStyle STYLE = GizmoStyle.stroke(COLOR, 4.0f);
	/** Pushed a little further out than the selection box, so the two do not overlap on a block at its edge. */
	private static final double OUTSET = 0.02;

	/** Whether the server is waiting for this player's click. */
	private static volatile boolean armed;

	private PendingClickHighlight() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PendingClickPayload.TYPE, (payload, context) -> armed = payload.armed());
		// The server forgets the click when the player leaves, so the highlight goes too.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> armed = false);
		LevelRenderEvents.END_EXTRACTION.register(PendingClickHighlight::emit);
	}

	/** Whether the server is waiting for this player to click a block. */
	public static boolean armed() {
		return armed;
	}

	private static void emit(LevelExtractionContext context) {
		if (!armed || !(Minecraft.getInstance().hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		Gizmos.cuboid(new AABB(hit.getBlockPos()).inflate(OUTSET), STYLE);
	}
}
