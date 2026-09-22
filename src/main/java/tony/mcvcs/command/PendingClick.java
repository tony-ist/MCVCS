package tony.mcvcs.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import tony.mcvcs.MCVCS;

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
 * Only the server thread touches this; a player's entry goes when they click or leave.
 */
public final class PendingClick {
	/** Event phase the click handlers run in, after the default one WorldEdit's tools listen in. */
	public static final Identifier PHASE = MCVCS.id("pending_click");
	/** What each armed player's next click does, by player UUID. */
	private static final Map<UUID, Action> PENDING = new HashMap<>();

	/** What a command does with the block the player clicks. */
	@FunctionalInterface
	public interface Action {
		void onClick(ServerPlayer player, ServerLevel level, BlockPos pos);
	}

	private PendingClick() {
	}

	public static void register() {
		// A click armed by a player who logged out must not do anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
		AttackBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		AttackBlockCallback.EVENT.register(PHASE, (player, level, hand, pos, direction) -> onClick(player, level, pos));
		UseBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		// Right-clicking with an item in hand would place or use it; only the empty main hand is a click here. The off
		// hand is tried after the main one, so with nothing in either the main hand gets there first.
		UseBlockCallback.EVENT.register(PHASE, (player, level, hand, hit) ->
			hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).isEmpty() ? onClick(player, level, hit.getBlockPos()) : InteractionResult.PASS);
	}

	/** Makes {@code player}'s next click run {@code action}, instead of whatever an earlier call asked for. */
	public static void arm(ServerPlayer player, Action action) {
		PENDING.put(player.getUUID(), action);
	}

	/** Leaves {@code player}'s next click to do what it normally does. */
	public static void disarm(ServerPlayer player) {
		PENDING.remove(player.getUUID());
	}

	private static InteractionResult onClick(Player player, Level level, BlockPos pos) {
		// The client fires the same events for its own player; only the server acts, and it tells the client what
		// became of the block.
		if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		Action action = PENDING.remove(serverPlayer.getUUID());
		if (action == null) {
			return InteractionResult.PASS;
		}
		action.onClick(serverPlayer, serverLevel, pos);
		return InteractionResult.SUCCESS;
	}
}
