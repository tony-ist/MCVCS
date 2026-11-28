package tony.mcvcs.command;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxExpansion;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.ResizeBeginPayload;
import tony.mcvcs.network.ResizeClearPayload;
import tony.mcvcs.network.ResizeMovePayload;

/**
 * {@code /vcs resize}: lets the player drag the faces of the selected placement's box with the mouse wheel, see the
 * client's {@code ClientResize}; {@code /vcs confirmResize} then saves the new box as the build's next version, the way
 * {@code /vcs fit} does, and {@code /vcs cancelResize} drops it. The client reports every change with a
 * {@link ResizeMovePayload}; the box is only checked here when it is confirmed.
 */
public final class VcsCommandResize {
	/** The resize each player has going, by player UUID. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	/**
	 * @param build     the build being resized
	 * @param placement the placement being resized
	 * @param original  its box when the resize began; a box changed since by anything else is not resized
	 * @param box       the box the player has dragged it to
	 */
	private record Pending(String build, String placement, BuildBox original, BuildBox box) {
		String label() {
			return build + Build.LABEL_SEPARATOR + placement;
		}
	}

	static final VcsHelp HELP = new VcsHelp("resize", "/vcs resize",
		"drag the sides of the box, then save",
		"Lets you resize the selected build's box. Hold Left Alt and scroll to move the side you are looking at. The box turns red if it overlaps another copy. Press Numpad 5 or run `/vcs confirmResize` to save it as a new version, or `/vcs cancelResize` to stop. Needs MCVCS on your client.");
	static final VcsHelp CONFIRM_HELP = new VcsHelp("confirmResize", "/vcs confirmResize",
		"save the box you are resizing",
		"Saves the resized box, with whatever is inside it, as a new version. Refuses if it overlaps another copy or is over " + BoxExpansion.MAX_VOLUME + " blocks.");
	static final VcsHelp CANCEL_HELP = new VcsHelp("cancelResize", "/vcs cancelResize",
		"stop resizing",
		"Stops resizing and leaves the box as it was.");

	private VcsCommandResize() {
	}

	/** Registers the payloads, forgets a player's resize when they leave, and listens for the changes their client makes. */
	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ResizeBeginPayload.TYPE, ResizeBeginPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ResizeClearPayload.TYPE, ResizeClearPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ResizeMovePayload.TYPE, ResizeMovePayload.STREAM_CODEC);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
		ServerPlayNetworking.registerGlobalReceiver(ResizeMovePayload.TYPE, (payload, context) -> {
			// A change still in flight when the resize ended is dropped.
			PENDING.computeIfPresent(context.player().getUUID(), (uuid, pending) -> new Pending(pending.build(), pending.placement(), pending.original(), payload.box()));
		});
	}

	/** {@code /vcs resize}: starts resizing the selected placement, replacing any resize the player had going. */
	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}
		if (!ServerPlayNetworking.canSend(player, ResizeBeginPayload.TYPE)) {
			source.sendFailure(Component.literal("Resizing needs MCVCS on your client; use ").append(ChatButtons.command("/vcs fit")).append(" instead"));
			return 0;
		}

		BuildPlacement placement = selected.get();
		BuildBox box = placement.box();
		PENDING.put(player.getUUID(), new Pending(placement.build().name(), placement.name(), box, box));
		ServerPlayNetworking.send(player, new ResizeBeginPayload(placement.label(), placement.dimension(), box));
		source.sendSuccess(() -> Component.literal("Resizing ").append(VcsMessages.placement(placement)).append(" (" + VcsMessages.size(box) + ")")
			.append("; hold Left Alt and scroll to move the side you are looking at, then press Numpad 5 or run ").append(ChatButtons.command("/vcs confirmResize"))
			.append(" to save, or ").append(ChatButtons.command("/vcs cancelResize")).append(" to stop"), false);
		return 1;
	}

	/**
	 * {@code /vcs confirmResize}: saves the dragged box as the build's next version. A refusal the player can fix by
	 * dragging on keeps the resize going; one they cannot ends it.
	 */
	static int confirm(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.literal("Nothing to resize; run ").append(ChatButtons.command("/vcs resize")).append(" first"));
			return 0;
		}

		Optional<BuildPlacement> found = BuildRegistry.find(source.getServer(), pending.build()).flatMap(build -> BuildPlacement.of(build, pending.placement()));
		if (found.isEmpty()) {
			drop(player);
			source.sendFailure(Component.empty().append(VcsMessages.name(pending.label())).append(" is gone; nothing was resized"));
			return 0;
		}
		BuildPlacement placement = found.get();
		if (!placement.box().equals(pending.original())) {
			drop(player);
			source.sendFailure(Component.literal("The box of ").append(VcsMessages.placement(placement)).append(" changed since the resize began; run ")
				.append(ChatButtons.command("/vcs resize")).append(" again"));
			return 0;
		}
		ServerLevel level = source.getServer().getLevel(placement.dimension());
		if (level == null) {
			drop(player);
			source.sendFailure(VcsMessages.missingDimension(placement));
			return 0;
		}

		BuildBox box = placement.box();
		BuildBox resized = pending.box();
		if (resized.equals(box)) {
			source.sendFailure(Component.literal("The box of ").append(VcsMessages.placement(placement)).append(" is unchanged; move a side first, or run ")
				.append(ChatButtons.command("/vcs cancelResize")));
			return 0;
		}
		if (resized.volume() > BoxExpansion.MAX_VOLUME) {
			source.sendFailure(Component.literal("Resizing ").append(VcsMessages.placement(placement)).append(" to " + VcsMessages.size(resized) + " (" + resized.volume()
				+ " blocks) would go past the limit of " + BoxExpansion.MAX_VOLUME + " blocks"));
			return 0;
		}
		Optional<BuildPlacement> overlapping = BuildRegistry.overlapping(source.getServer(), placement.dimension(), resized, placement);
		if (overlapping.isPresent()) {
			source.sendFailure(Component.literal("Resizing ").append(VcsMessages.placement(placement)).append(" to " + VcsMessages.size(resized))
				.append(" would overlap ").append(VcsMessages.placement(overlapping.get())).append(VcsMessages.NO_OVERLAP));
			return 0;
		}

		Build build = placement.build().withNextVersion(resized.relativeTo(placement.origin()));
		int version = build.version();
		build = build.withPlacement(placement.name(), placement.placement().withHead(version));
		try {
			BuildSaver.save(player, build, version, resized, level);
			drop(player);
			BuildRegistry.select(player, build, placement.name());
			source.sendSuccess(() -> Component.literal("Resized ").append(VcsMessages.placement(placement)).append(" from " + VcsMessages.size(box) + " (" + box.volume() + " blocks) to "
				+ VcsMessages.size(resized) + " (" + resized.volume() + " blocks) and saved it as v" + version), false);
			return 1;
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to resize '{}' to v{} for {}", placement.label(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save: " + e.getMessage()));
			return 0;
		}
	}

	/** {@code /vcs cancelResize}. */
	static int cancel(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null) {
			source.sendFailure(Component.literal("Nothing to cancel; run ").append(ChatButtons.command("/vcs resize")).append(" first"));
			return 0;
		}
		drop(player);
		source.sendSuccess(() -> Component.literal("Stopped resizing ").append(VcsMessages.name(pending.label())).append("; its box is unchanged"), false);
		return 1;
	}

	/** Forgets the player's resize and stops their client showing it. */
	private static void drop(ServerPlayer player) {
		PENDING.remove(player.getUUID());
		ServerPlayNetworking.send(player, ResizeClearPayload.INSTANCE);
	}
}
