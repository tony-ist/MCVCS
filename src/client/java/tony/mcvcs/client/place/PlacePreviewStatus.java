package tony.mcvcs.client.place;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import org.jspecify.annotations.Nullable;

/**
 * Whether the copy a {@code /vcs place} is showing could be placed where it stands, worked out on the client so the
 * player sees it while they are still moving it: the box is drawn green while {@code /vcs confirmPlace} would
 * succeed and red while it would be refused.
 * <p>
 * Both reasons the server refuses are checked here, against what the client already knows: the placements it was
 * sent, which may never be overlapped, and the blocks around it, which are only overwritten with {@code -f}. The
 * server checks again when the placement is confirmed and has the last word; this is only a light ahead of the
 * command.
 * <p>
 * Overlaps are looked for every tick, there being a handful of placements at most. The blocks in the way are counted
 * only when the copy moves, since that means reading the whole box, and a box of more than {@link #SCAN_LIMIT}
 * blocks is not read at all: counting millions of blocks would cost more than the warning is worth, so a copy that
 * big is drawn green until the server says otherwise.
 */
public final class PlacePreviewStatus {
	/** Largest box, in blocks, whose contents are counted; see above. */
	public static final long SCAN_LIMIT = 200_000;

	/** What the copy stands over right now; null when none is being shown. */
	private static volatile @Nullable Status status;

	/**
	 * @param box         where the copy stands, so a status is never read for a box it was not worked out for
	 * @param overlapping the label of the placement it overlaps, or null if it overlaps none
	 * @param inTheWay    how many blocks stand inside the box, or 0 when the box was too big to count
	 * @param force       whether the {@code /vcs place} was given {@code -f}, which overwrites those blocks
	 */
	public record Status(BuildBox box, @Nullable String overlapping, int inTheWay, boolean force) {
		/** Whether {@code /vcs confirmPlace} would put the copy down as things stand. */
		public boolean placeable() {
			return overlapping == null && (force || inTheWay == 0);
		}

		/** Why it would be refused, in a few words for the action bar, or null when it would not be. */
		public @Nullable String reason() {
			if (overlapping != null) {
				return "overlaps " + overlapping;
			}
			if (inTheWay > 0 && !force) {
				return inTheWay + (inTheWay == 1 ? " block" : " blocks") + " in the way";
			}
			return null;
		}
	}

	private PlacePreviewStatus() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PlacePreviewStatus::refresh);
	}

	/** What the copy stands over, or null when none is shown or it has not been looked at yet. */
	public static @Nullable Status status() {
		return status;
	}

	/**
	 * Works the status out again. Called every tick, and straight after a move so the message that reports it is
	 * never a tick behind.
	 */
	public static void refresh(Minecraft client) {
		PlacePreview preview = PreviewManager.place();
		ClientLevel level = client.level;
		if (preview == null || level == null || !preview.blocks().dimension().equals(level.dimension())) {
			status = null;
			return;
		}

		BuildBox box = preview.box();
		Status previous = status;
		// The blocks inside the box only change when the copy moves, or when someone builds there, which the next
		// move picks up; the overlap is cheap enough to ask about every time.
		int inTheWay = previous != null && previous.box().equals(box) ? previous.inTheWay() : inTheWay(level, box);
		status = new Status(box, overlapping(box, level.dimension()), inTheWay, preview.force());
	}

	/** The label of the placement {@code box} intersects in {@code dimension}, or null if it intersects none. */
	private static @Nullable String overlapping(BuildBox box, ResourceKey<Level> dimension) {
		for (ClientPlacement placement : ClientPlacements.all()) {
			if (placement.dimension().equals(dimension) && placement.box().intersects(box)) {
				return placement.label();
			}
		}
		return null;
	}

	/** How many blocks stand inside {@code box}, or 0 when it is too big to be worth counting. */
	private static int inTheWay(ClientLevel level, BuildBox box) {
		if (box.volume() > SCAN_LIMIT) {
			return 0;
		}
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
			if (!level.getBlockState(pos).isAir()) {
				count++;
			}
		}
		return count;
	}
}
