package tony.mcvcs.client.selection;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.network.PendingClickPayload;

/**
 * While a {@code /vcs create} or {@code /vcs select} waits for the player to click a block, outlines the block under
 * the crosshair with a thick line, so the player can see which block their punch would hand over; for the second corner
 * of {@code /vcs create -b}, the whole box from the first corner to that block. The server says when a click starts
 * and stops being waited for, see {@link PendingClickPayload}.
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
	/** The box corner the player already picked, if the click waited for is the other one; null otherwise. */
	private static volatile @Nullable BlockPos corner;
	/** The build the click waited for would create, if {@code /vcs create} is waiting for it; null otherwise. */
	private static volatile @Nullable String creating;

	private PendingClickHighlight() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PendingClickPayload.TYPE, (payload, context) -> {
			corner = payload.corner().orElse(null);
			creating = payload.creating().orElse(null);
			armed = payload.armed();
		});
		// The server forgets the click when the player leaves, so the highlight goes too.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			armed = false;
			corner = null;
			creating = null;
		});
		LevelRenderEvents.END_EXTRACTION.register(PendingClickHighlight::emit);
	}

	/** Whether the server is waiting for this player to click a block. */
	public static boolean armed() {
		return armed;
	}

	/** The build the server waits for this player's click to create, or null if no {@code /vcs create} is waiting. */
	public static @Nullable String creating() {
		return armed ? creating : null;
	}

	/** The box corner already picked while the server waits for the other one; null otherwise. */
	public static @Nullable BlockPos corner() {
		return corner;
	}

	/**
	 * Outlines the block in sight or, while the second corner of a box is waited for, the box from the first corner to
	 * that block, or to the first corner alone when no block is in sight.
	 */
	private static void emit(LevelExtractionContext context) {
		if (!armed) {
			return;
		}
		BlockPos first = corner;
		BlockPos target = Minecraft.getInstance().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
		if (first == null && target == null) {
			return;
		}
		BlockPos a = first != null ? first : target;
		BlockPos b = target != null ? target : first;
		Gizmos.cuboid(AABB.encapsulatingFullBlocks(a, b).inflate(OUTSET), STYLE);
	}
}
