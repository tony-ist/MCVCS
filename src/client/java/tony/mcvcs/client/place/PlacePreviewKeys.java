package tony.mcvcs.client.place;

import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.place.PlacePreviewStatus.Status;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.selection.SelectHotkey;
import tony.mcvcs.network.PlacePreviewMovePayload;
import tony.mcvcs.build.BuildBox;
import org.jspecify.annotations.Nullable;

/**
 * The keys that line up the copy a {@code /vcs place} is showing, before {@code /vcs confirmPlace} puts it into the
 * world. They are the numpad by default and normal key mappings, so they can be rebound under Options, Controls,
 * Key Binds in the MCVCS category, and they do nothing while no copy is being shown.
 * <p>
 * Which way the copy goes is read off the box itself: the key presses are about the face of the box the player is
 * looking at, not about compass directions. {@code 8} pushes the copy away through the face in sight and {@code 2}
 * pulls it back, {@code 4} and {@code 6} slide it sideways along that face, left and right as the player sees it,
 * with no change of height. Looking at the top or the bottom of the box, or away from it altogether, leaves those
 * four with nothing to go by, so they move nothing and say so instead. {@code 7} and {@code 9} raise and lower the
 * copy, which needs no face at all. {@code 5} places it, running {@code /vcs confirmPlace} for the player, and says
 * why instead when the copy stands where it cannot be placed.
 * <p>
 * Each press moves one block, or {@link ClientConfig#sprintStep()} of them while the sprint key is held. The client
 * draws the copy in its new place at once and tells the server where it went, see {@link PlacePreviewMovePayload};
 * nothing is put into the world until the placement is confirmed.
 */
public final class PlacePreviewKeys {
	/** How far ahead, in blocks, the box can be and still be looked at; the same reach the select hotkey has. */
	private static final double RANGE = SelectHotkey.RANGE;
	/** How near a hit has to be to a side of the box, in blocks, to count as that side; a hit is on it exactly. */
	private static final double TOLERANCE = 1.0e-3;
	/** The faces {@code 8}, {@code 2}, {@code 4} and {@code 6} work off: the four sides, never the top or bottom. */
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	/** Pushes the copy away from the player, through the side of the box they are looking at. */
	public static final KeyMapping AWAY = key("place_away", GLFW.GLFW_KEY_KP_8);
	/** Pulls the copy back towards the player, the other way from {@link #AWAY}. */
	public static final KeyMapping CLOSER = key("place_closer", GLFW.GLFW_KEY_KP_2);
	/** Slides the copy to the player's left along the side they are looking at, at the same height. */
	public static final KeyMapping LEFT = key("place_left", GLFW.GLFW_KEY_KP_4);
	/** Slides the copy to the player's right, the other way from {@link #LEFT}. */
	public static final KeyMapping RIGHT = key("place_right", GLFW.GLFW_KEY_KP_6);
	/** Raises the copy. */
	public static final KeyMapping UP = key("place_up", GLFW.GLFW_KEY_KP_7);
	/** Lowers the copy. */
	public static final KeyMapping DOWN = key("place_down", GLFW.GLFW_KEY_KP_9);
	/** Places the copy where it stands, as {@code /vcs confirmPlace} does. */
	public static final KeyMapping CONFIRM = key("place_confirm", GLFW.GLFW_KEY_KP_5);

	/** Shown when a sideways key is pressed while no side of the box is in sight, since there is nothing to go by. */
	public static final String HINT = "Look at the build's side face to move it with hotkeys";
	/** What {@code 5} says instead of placing when the copy stands where it cannot be placed; the reason follows. */
	public static final String REFUSED = "Cannot place here - ";
	/** The command {@code 5} runs, the same one the player would type. */
	private static final String CONFIRM_COMMAND = "vcs confirmPlace";

	private PlacePreviewKeys() {
	}

	private static KeyMapping key(String name, int code) {
		return new KeyMapping("key.mcvcs." + name, InputConstants.Type.KEYSYM, code, SelectHotkey.CATEGORY);
	}

	/** Must run from the client entrypoint: key mappings can only be added before the options are loaded. */
	public static void register() {
		for (KeyMapping key : new KeyMapping[] {AWAY, CLOSER, LEFT, RIGHT, UP, DOWN, CONFIRM}) {
			KeyMappingHelper.registerKeyMapping(key);
		}
		ClientTickEvents.END_CLIENT_TICK.register(PlacePreviewKeys::tick);
	}

	private static void tick(Minecraft client) {
		// Presses are queued until a tick looks at them, so several pressed within one tick all move the copy.
		while (AWAY.consumeClick()) {
			alongFace(client, true);
		}
		while (CLOSER.consumeClick()) {
			alongFace(client, false);
		}
		while (LEFT.consumeClick()) {
			acrossFace(client, true);
		}
		while (RIGHT.consumeClick()) {
			acrossFace(client, false);
		}
		while (UP.consumeClick()) {
			move(client, Direction.UP);
		}
		while (DOWN.consumeClick()) {
			move(client, Direction.DOWN);
		}
		while (CONFIRM.consumeClick()) {
			confirm(client);
		}
	}

	/**
	 * {@code 5}: places the copy where it stands. The client knows what the server would refuse, see
	 * {@link PlacePreviewStatus}, so a copy that cannot go there says why instead of running a command that is bound
	 * to fail; what the client cannot tell, the server still has the last word on.
	 */
	private static void confirm(Minecraft client) {
		PlacePreview preview = PreviewManager.place();
		LocalPlayer player = client.player;
		if (preview == null || player == null) {
			return;
		}

		Status status = PlacePreviewStatus.status();
		String reason = status == null || !status.box().equals(preview.box()) ? null : status.reason();
		if (reason != null) {
			player.sendOverlayMessage(Component.literal(REFUSED + reason).withStyle(ChatFormatting.RED));
			return;
		}
		// Sent as if typed, so the server checks the permission and answers in chat the way it does for the command.
		player.connection.sendCommand(CONFIRM_COMMAND);
	}

	/** {@code 8} and {@code 2}: through the side of the box in sight, away from the player or back towards them. */
	private static void alongFace(Minecraft client, boolean away) {
		Direction face = facing(client);
		if (face != null) {
			// The face in sight looks back at the player, so moving away from them goes the other way.
			move(client, away ? face.getOpposite() : face);
		}
	}

	/** {@code 4} and {@code 6}: sideways along the side of the box in sight, left or right as the player sees it. */
	private static void acrossFace(Minecraft client, boolean left) {
		Direction face = facing(client);
		if (face != null) {
			// Looking at the north side, the player faces south, and their left hand points east.
			Direction forward = face.getOpposite();
			move(client, left ? forward.getCounterClockWise() : forward.getClockWise());
		}
	}

	/**
	 * The side of the copy's box the player is looking at, or null, having told them why, when none is: a copy that
	 * is not being shown, the top or the bottom of the box, or nothing of it at all.
	 */
	private static @Nullable Direction facing(Minecraft client) {
		PlacePreview preview = PreviewManager.place();
		LocalPlayer player = client.player;
		if (preview == null || player == null || !preview.blocks().dimension().equals(player.level().dimension())) {
			return null;
		}

		Direction face = sideLookedAt(aabb(preview.box()), player.getEyePosition(), player.getViewVector(1.0f));
		if (face == null) {
			player.sendOverlayMessage(Component.literal(HINT));
		}
		return face;
	}

	/** Slides the copy {@code direction} by a block, or by {@link ClientConfig#sprintStep()} while sprint is held. */
	private static void move(Minecraft client, Direction direction) {
		PlacePreview preview = PreviewManager.place();
		LocalPlayer player = client.player;
		// A copy left standing in another dimension is neither seen nor moved from here.
		if (preview == null || player == null || !preview.blocks().dimension().equals(player.level().dimension())) {
			return;
		}

		int step = client.options.keySprint.isDown() ? ClientConfig.sprintStep() : 1;
		BlockPos min = preview.box().min().relative(direction, step);
		PreviewManager.movePlace(min, client);
		ClientPlayNetworking.send(new PlacePreviewMovePayload(min));

		// The move changed what the copy stands over, and the message about to be sent reports it.
		PlacePreviewStatus.refresh(client);
		player.sendOverlayMessage(moved(preview, min));
	}

	/** What the action bar says after a move: where the copy now starts, and why it could not be placed there. */
	private static MutableComponent moved(PlacePreview preview, BlockPos min) {
		MutableComponent message = Component.literal(preview.blocks().name() + " at " + min.toShortString());
		Status status = PlacePreviewStatus.status();
		String reason = status == null ? null : status.reason();
		if (reason != null) {
			message.append(Component.literal(" - " + reason).withStyle(ChatFormatting.RED));
		}
		return message;
	}

	/**
	 * Which side of {@code aabb} the ray from {@code eye} along {@code look} hits within {@link #RANGE} blocks, or
	 * null when it hits the top, the bottom or nothing at all.
	 * <p>
	 * The hit is a point on the surface of the box, so the side it belongs to is the one whose plane the point lies
	 * in; a hit along an edge lies in two, and the nearer plane wins. A player standing inside the box has no side
	 * in sight, since a ray that starts inside never enters.
	 */
	public static @Nullable Direction sideLookedAt(AABB aabb, Vec3 eye, Vec3 look) {
		Optional<Vec3> hit = aabb.clip(eye, eye.add(look.normalize().scale(RANGE)));
		if (hit.isEmpty()) {
			return null;
		}

		Vec3 point = hit.get();
		Direction nearest = null;
		double nearestDistance = TOLERANCE;
		for (Direction side : SIDES) {
			double distance = distanceTo(point, aabb, side);
			if (distance < nearestDistance) {
				nearest = side;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/** How far {@code point} is from the plane the {@code side} face of {@code aabb} lies in. */
	private static double distanceTo(Vec3 point, AABB aabb, Direction side) {
		return switch (side) {
			case NORTH -> Math.abs(point.z - aabb.minZ);
			case SOUTH -> Math.abs(point.z - aabb.maxZ);
			case WEST -> Math.abs(point.x - aabb.minX);
			case EAST -> Math.abs(point.x - aabb.maxX);
			default -> Double.MAX_VALUE;
		};
	}

	/** The box as the blocks it covers, the whole of the outermost ones included. */
	public static AABB aabb(BuildBox box) {
		return AABB.encapsulatingFullBlocks(box.min(), box.max());
	}
}
