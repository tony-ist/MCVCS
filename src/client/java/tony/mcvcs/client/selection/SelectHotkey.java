package tony.mcvcs.client.selection;

import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import tony.mcvcs.MCVCS;
import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.build.ClientBuild;
import org.jspecify.annotations.Nullable;

/**
 * A key that selects the build under the crosshair, as {@code /vcs select} would, so a build can be picked by looking
 * at it instead of typing its name.
 * <p>
 * The key is a normal key mapping, {@code V} unless rebound under Options, Controls, Key Binds in the MCVCS category.
 * Each press casts a ray from the player's eyes along their line of sight and takes the nearest build whose box it
 * passes through, or the build the player is standing in, which is nearer than anything else could be. The box is
 * what counts, not the blocks in it: looking through a doorway or over the wall of a build still hits its box, and an
 * empty region of the box is as good as a solid one. The selection itself is made by the server, so the client only
 * sends it the command; the reply, and the box being drawn, come back the same way as after typing the command.
 */
public final class SelectHotkey {
	/** How far ahead, in blocks, a build can be and still be selected. Far enough for a build across a large plot. */
	public static final double RANGE = 128.0;
	/** Category the key is listed under in the controls screen, named by {@code key.category.mcvcs.main}. */
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MCVCS.id("main"));
	/** The key itself, named by {@code key.mcvcs.select_looked_at}; {@code V} by default, which nothing in vanilla uses. */
	public static final KeyMapping KEY = new KeyMapping("key.mcvcs.select_looked_at", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);

	private SelectHotkey() {
	}

	/** Must run from the client entrypoint: key mappings can only be added before the options are loaded. */
	public static void register() {
		KeyMappingHelper.registerKeyMapping(KEY);
		ClientTickEvents.END_CLIENT_TICK.register(SelectHotkey::tick);
	}

	private static void tick(Minecraft client) {
		// Presses are queued until a tick looks at them, so several pressed within one tick are all seen here.
		while (KEY.consumeClick()) {
			press(client);
		}
	}

	private static void press(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		ClientBuild build = lookedAt(ClientBuilds.all(), player.level().dimension(), player.getEyePosition(), player.getViewVector(1.0f), RANGE);
		if (build == null) {
			player.sendOverlayMessage(Component.literal("No build in sight"));
			return;
		}
		if (build.equals(ClientBuilds.selected())) {
			player.sendOverlayMessage(Component.literal("Build ").append(name(build)).append(" is already selected"));
			return;
		}
		// Sent as if typed, so the server checks the permission and answers in chat the way it does for the command.
		player.connection.sendCommand("vcs select " + build.name());
	}

	/**
	 * The nearest of {@code builds} whose box in {@code dimension} the ray from {@code eye} along {@code look} passes
	 * through within {@code range} blocks, or the build whose box {@code eye} is inside; {@code null} if there is none.
	 * Builds cannot overlap, so at most one box contains the eye.
	 */
	public static @Nullable ClientBuild lookedAt(List<ClientBuild> builds, ResourceKey<Level> dimension, Vec3 eye, Vec3 look, double range) {
		Vec3 end = eye.add(look.normalize().scale(range));
		ClientBuild nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (ClientBuild build : builds) {
			if (!build.dimension().equals(dimension)) {
				continue;
			}
			AABB aabb = AABB.encapsulatingFullBlocks(build.box().min(), build.box().max());
			double distance;
			if (aabb.contains(eye)) {
				// A ray starting inside a box never enters it, so clip would miss the box the player is standing in.
				distance = 0.0;
			} else {
				Optional<Vec3> hit = aabb.clip(eye, end);
				if (hit.isEmpty()) {
					continue;
				}
				distance = hit.get().distanceToSqr(eye);
			}
			if (distance < nearestDistance) {
				nearest = build;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/** A build's name as it appears in chat: light blue, the same as the server's messages show it. */
	private static Component name(ClientBuild build) {
		return Component.literal(build.name()).withStyle(ChatFormatting.AQUA);
	}
}
