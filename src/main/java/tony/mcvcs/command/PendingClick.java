package tony.mcvcs.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import tony.mcvcs.MCVCS;
import tony.mcvcs.network.PendingClickPayload;

/**
 * The block a command asks the player to click. {@code /vcs create} grows a build from it, and {@code /vcs select}
 * takes the placement it belongs to; either way the command {@linkplain #arm arms} the player and their next punch of
 * a block, with anything or nothing in hand, or right-click of one with an empty main hand, hands the block over. The
 * click does nothing else: the block is neither broken nor used, and Fabric has the server tell the client so, in case
 * it already broke or toggled the block on its side.
 * <p>
 * WorldEdit's own tools go first, see {@link #PHASE}: a click WorldEdit takes, such as the wand setting a position or
 * a brush painting, is not a click here, and the player stays armed for the next one. A player has one armed click at
 * a time, so a second command replaces whatever an earlier one left waiting.
 * <p>
 * While armed, the player's action bar says what the click will do, sent again every {@link #HINT_INTERVAL} ticks so
 * it does not fade, and taken off as soon as the click is used or disarmed. It is an ordinary action bar message, so
 * players without the mod on their client see it too; a client with the mod is also sent a {@link PendingClickPayload}
 * so it can outline the block the click would hand over.
 * <p>
 * Only the server thread touches this; a player's entry goes when they click or leave.
 */
public final class PendingClick {
	/** Event phase the click handlers run in, after the default one WorldEdit's tools listen in. */
	public static final Identifier PHASE = MCVCS.id("pending_click");
	/**
	 * How often, in ticks, an armed player's hint is sent again. The client fades an action bar message out after three
	 * seconds, so once a second keeps it on screen for as long as the click is waited for.
	 */
	private static final int HINT_INTERVAL = 20;
	/** What each armed player's next click does, by player UUID. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	/** What a command does with the block the player clicks. */
	@FunctionalInterface
	public interface Action {
		void onClick(ServerPlayer player, ServerLevel level, BlockPos pos);
	}

	/** A click being waited for, and the action bar hint that says what it will do. */
	private record Pending(Component hint, Action action) {
	}

	private PendingClick() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(PendingClickPayload.TYPE, PendingClickPayload.STREAM_CODEC);
		// A click armed by a player who logged out must not do anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % HINT_INTERVAL != 0) {
				return;
			}
			PENDING.forEach((uuid, pending) -> {
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.sendOverlayMessage(pending.hint());
				}
			});
		});
		AttackBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		AttackBlockCallback.EVENT.register(PHASE, (player, level, hand, pos, direction) -> onClick(player, level, pos));
		UseBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		// Right-clicking with an item in hand would place or use it; only the empty main hand is a click here. The off
		// hand is tried after the main one, so with nothing in either the main hand gets there first.
		UseBlockCallback.EVENT.register(PHASE, (player, level, hand, hit) ->
			hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).isEmpty() ? onClick(player, level, hit.getBlockPos()) : InteractionResult.PASS);
	}

	/**
	 * Makes {@code player}'s next click run {@code action}, instead of whatever an earlier call asked for, and keeps
	 * {@code hint} on their action bar until then.
	 */
	public static void arm(ServerPlayer player, Component hint, Action action) {
		PENDING.put(player.getUUID(), new Pending(hint, action));
		player.sendOverlayMessage(hint);
		if (ServerPlayNetworking.canSend(player, PendingClickPayload.TYPE)) {
			ServerPlayNetworking.send(player, new PendingClickPayload(true));
		}
	}

	/** Leaves {@code player}'s next click to do what it normally does. */
	public static void disarm(ServerPlayer player) {
		if (PENDING.remove(player.getUUID()) != null) {
			ended(player);
		}
	}

	/** Takes the hint off the action bar at once, rather than leaving it to fade, and the highlight off the client. */
	private static void ended(ServerPlayer player) {
		player.sendOverlayMessage(Component.empty());
		if (ServerPlayNetworking.canSend(player, PendingClickPayload.TYPE)) {
			ServerPlayNetworking.send(player, new PendingClickPayload(false));
		}
	}

	private static InteractionResult onClick(Player player, Level level, BlockPos pos) {
		// The client fires the same events for its own player; only the server acts, and it tells the client what
		// became of the block.
		if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		Pending pending = PENDING.remove(serverPlayer.getUUID());
		if (pending == null) {
			return InteractionResult.PASS;
		}
		ended(serverPlayer);
		pending.action().onClick(serverPlayer, serverLevel, pos);
		return InteractionResult.SUCCESS;
	}
}
