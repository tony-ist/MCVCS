package tony.mcvcs.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import tony.mcvcs.network.ChatButtons;

/**
 * Routes clicks on the mod's chat buttons to {@link ChatButtons#handle}. Vanilla only logs custom click actions and
 * drops the player on the way, so the click is picked up here, where the player is known, once the packet has been
 * moved to the server thread.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin {
	@Inject(
		method = "handleCustomClickAction",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"),
		cancellable = true
	)
	private void mcvcs$handleChatButton(ServerboundCustomClickActionPacket packet, CallbackInfo info) {
		// Only in-game clicks have a player; a click during configuration cannot be one of ours.
		if ((Object) this instanceof ServerGamePacketListenerImpl game && ChatButtons.handle(game.player, packet.id(), packet.payload())) {
			info.cancel();
		}
	}
}
