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
 * {@code /vcs create <buildname>} run without a WorldEdit selection: the build is made from the next block the player
 * clicks instead. The command {@linkplain #arm arms} the player; their next punch of a block, with anything or nothing
 * in hand, or right-click of one with an empty main hand, hands the block to {@link VcsCommandCreate#createFromBlock}, which
 * grows a box from it over everything connected to it and creates the build from that. The click does nothing else: the
 * block is neither broken nor used, and Fabric has the server tell the client so, in case it already broke or toggled
 * the block on its side.
 * <p>
 * WorldEdit's own tools go first, see {@link #PHASE}: a click WorldEdit takes, such as the wand setting a position or a
 * brush painting, is not a click here, and the player stays armed for the next one. Every {@code /vcs create} replaces
 * whatever an earlier one left armed, so making a selection with the wand and running the command again creates the
 * build from the selection as usual.
 * <p>
 * Only the server thread touches this; a player's entry goes when they click or leave.
 */
public final class CreateOnClick {
	/** Event phase the click handlers run in, after the default one WorldEdit's tools listen in. */
	public static final Identifier PHASE = MCVCS.id("create_on_click");
	/** The build name each armed player's next click creates, by player UUID. */
	private static final Map<UUID, String> PENDING = new HashMap<>();

	private CreateOnClick() {
	}

	public static void register() {
		// A click armed by a player who logged out must not create anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
		AttackBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		AttackBlockCallback.EVENT.register(PHASE, (player, level, hand, pos, direction) -> onClick(player, level, pos));
		UseBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		// Right-clicking with an item in hand would place or use it; only the empty main hand is a click here. The off
		// hand is tried after the main one, so with nothing in either the main hand gets there first.
		UseBlockCallback.EVENT.register(PHASE, (player, level, hand, hit) ->
			hand == InteractionHand.MAIN_HAND && player.getItemInHand(hand).isEmpty() ? onClick(player, level, hit.getBlockPos()) : InteractionResult.PASS);
	}

	/** Makes {@code player}'s next click create the build called {@code name}, instead of whatever an earlier call asked for. */
	public static void arm(ServerPlayer player, String name) {
		PENDING.put(player.getUUID(), name);
	}

	/** Leaves {@code player}'s next click to do what it normally does. */
	public static void disarm(ServerPlayer player) {
		PENDING.remove(player.getUUID());
	}

	private static InteractionResult onClick(Player player, Level level, BlockPos pos) {
		// The client fires the same events for its own player; only the server creates, and it tells the client what
		// became of the block.
		if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		String name = PENDING.remove(serverPlayer.getUUID());
		if (name == null) {
			return InteractionResult.PASS;
		}
		VcsCommandCreate.createFromBlock(serverPlayer, serverLevel, name, pos);
		return InteractionResult.SUCCESS;
	}
}
