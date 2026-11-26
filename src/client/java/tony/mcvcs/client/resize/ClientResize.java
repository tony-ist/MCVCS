package tony.mcvcs.client.resize;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.network.ResizeBeginPayload;
import tony.mcvcs.network.ResizeClearPayload;
import tony.mcvcs.network.ResizeMovePayload;
import org.jspecify.annotations.Nullable;

/**
 * The box a {@code /vcs resize} is showing, and the mouse wheel that drags its faces. The wheel and Numpad 5 are the
 * keys of {@link PlacePreviewKeys}, which hand them over here while no {@code /vcs place} copy is shown.
 * <p>
 * Pressing {@link PlacePreviewKeys#SCROLL} picks the face the wheel drags and keeps it until the key is let go, the
 * same way the {@code /vcs place} copy's axis is picked: the face looked at, a notch up pushing it away from the
 * player and a notch down pulling it closer, one block each. Looking at no face, it goes by the axis the player looks
 * along most, a notch up moving the face the way they look, see {@link #target}. A box is never thinner than one
 * block. Every change is sent to the server, which checks the box when it is confirmed.
 * <p>
 * The box overlapping another placement, or going past {@link BoxExpansion#MAX_VOLUME}, is worked out here as well,
 * so it is drawn red and said on the action bar while the player drags, see {@link #reason()}.
 */
public final class ClientResize {
	/** What Numpad 5 says instead of confirming when the box would be refused; the reason follows. */
	public static final String REFUSED = "Cannot resize - ";
	private static final String CONFIRM_COMMAND = "vcs confirmResize";
	private static final Direction[] FACES = Direction.values();

	/**
	 * @param label     the placement being resized, e.g. {@code tower/main}
	 * @param dimension where it stands
	 * @param box       the box as the player has dragged it so far
	 */
	public record Resize(String label, ResourceKey<Level> dimension, BuildBox box) {
	}

	private static volatile @Nullable Resize state;
	/** What the wheel drags, picked when {@link PlacePreviewKeys#SCROLL} was pressed, see {@link #target}. */
	private static @Nullable Target locked;

	private ClientResize() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ResizeBeginPayload.TYPE, (payload, context) -> {
			state = new Resize(payload.label(), payload.dimension(), payload.box());
			announce(context.client());
		});
		ClientPlayNetworking.registerGlobalReceiver(ResizeClearPayload.TYPE, (payload, context) -> state = null);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> state = null);
		// The target is picked as the key goes down, and forgotten as it comes up.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (target(client) == null) {
				locked = null;
			}
		});
	}

	/** The resize going on, or null when there is none. */
	public static @Nullable Resize active() {
		return state;
	}

	/** The resize going on in the dimension the player is in, which is the only one they can see or drag. */
	private static @Nullable Resize here(Minecraft client) {
		Resize resize = state;
		LocalPlayer player = client.player;
		return resize == null || player == null || !resize.dimension().equals(player.level().dimension()) ? null : resize;
	}

	/**
	 * A notch of the mouse wheel with {@link PlacePreviewKeys#SCROLL} held and no screen open; answers whether it was
	 * taken, which it is whenever a resize is going on here.
	 */
	public static boolean scrolled(Minecraft client, double amount) {
		Resize resize = here(client);
		if (resize == null) {
			return false;
		}

		Target target = target(client);
		if (target == null) {
			return true;
		}
		Direction direction = amount > 0.0 ? target.away() : target.away().getOpposite();
		BlockPos min = resize.box().min();
		BlockPos max = resize.box().max();
		if (target.face().getAxisDirection() == Direction.AxisDirection.POSITIVE) {
			max = max.relative(direction);
		} else {
			min = min.relative(direction);
		}
		if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
			client.player.sendOverlayMessage(Component.literal("The box cannot be thinner than one block").withStyle(ChatFormatting.RED));
			return true;
		}

		BuildBox box = new BuildBox(min, max);
		state = new Resize(resize.label(), resize.dimension(), box);
		ClientPlayNetworking.send(new ResizeMovePayload(box));
		announce(client);
		return true;
	}

	/** Numpad 5: commits the box, or says why it would be refused instead of running a command bound to fail. */
	public static void confirm(Minecraft client) {
		LocalPlayer player = client.player;
		if (state == null || player == null) {
			return;
		}
		String reason = reason();
		if (reason != null) {
			player.sendOverlayMessage(Component.literal(REFUSED + reason).withStyle(ChatFormatting.RED));
			return;
		}
		player.connection.sendCommand(CONFIRM_COMMAND);
	}

	/**
	 * Why {@code /vcs confirmResize} would refuse the box as it stands, in a few words, or null when it would not:
	 * it overlaps another placement, or it is too big.
	 */
	public static @Nullable String reason() {
		Resize resize = state;
		if (resize == null) {
			return null;
		}
		for (ClientPlacement placement : ClientPlacements.all()) {
			if (!placement.label().equals(resize.label()) && placement.dimension().equals(resize.dimension()) && placement.box().intersects(resize.box())) {
				return "overlaps " + placement.label();
			}
		}
		if (resize.box().volume() > BoxExpansion.MAX_VOLUME) {
			return "over the limit of " + BoxExpansion.MAX_VOLUME + " blocks";
		}
		return null;
	}

	/** Puts the box's size on the action bar, or in red why it would be refused. */
	private static void announce(Minecraft client) {
		Resize resize = state;
		LocalPlayer player = client.player;
		if (resize == null || player == null) {
			return;
		}
		String reason = reason();
		BuildBox box = resize.box();
		player.sendOverlayMessage(reason != null
			? Component.literal(reason).withStyle(ChatFormatting.RED)
			: Component.literal(box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ() + " (" + box.volume() + " blocks)"));
	}

	/**
	 * The face the wheel drags, and the way that is away from the player.
	 *
	 * @param face the face of the box that moves
	 * @param away the way a notch up moves it: into the box when seen from outside, out of it when seen from inside
	 */
	public record Target(Direction face, Direction away) {
	}

	/**
	 * What the wheel drags, picked when {@link PlacePreviewKeys#SCROLL} was pressed and kept until it is let go; null
	 * while the key is not held over a resize going on here, or while a {@code /vcs place} copy takes the wheel.
	 */
	public static @Nullable Target target(Minecraft client) {
		Resize resize = here(client);
		if (resize == null || client.screen != null || !PlacePreviewKeys.SCROLL.isDown() || PreviewManager.place() != null) {
			return null;
		}
		if (locked == null) {
			locked = pick(client, resize.box());
		}
		return locked;
	}

	/**
	 * The face the player looks at, pushed away from them by a notch up. Looking at none, it goes by the axis they
	 * look along most, a notch up moving a face the way they look: from outside the box the face towards them, from
	 * inside it the face ahead, so the box shrinks from the near side or grows ahead.
	 */
	private static Target pick(Minecraft client, BuildBox box) {
		AABB aabb = PlacePreviewKeys.aabb(box);
		Vec3 eye = client.player.getEyePosition();
		Vec3 look = client.player.getViewVector(1.0f);
		Direction face = PlacePreviewKeys.faceLookedAt(aabb, eye, look, FACES);
		if (face != null) {
			// The face in sight looks back at the player, so away from them is the other way.
			return new Target(face, face.getOpposite());
		}
		Direction ahead = Direction.getApproximateNearest(look);
		return new Target(aabb.contains(eye) ? ahead : ahead.getOpposite(), ahead);
	}
}
