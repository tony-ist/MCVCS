package tony.mcvcs.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

import tony.mcvcs.client.place.PlacePreviewKeys;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the mouse wheel move the copy {@code /vcs place} is showing, see
 * {@link PlacePreviewKeys#scrolled(Minecraft, double)}.
 * <p>
 * {@link MouseHandler#onScroll} is where every turn of the wheel arrives, before the game decides whether it changes
 * the held item or scrolls whatever screen is open. Taking a turn here is all it takes to move the copy instead, and
 * a turn that is taken is cancelled so nothing else acts on it; every other turn falls through untouched.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void mcvcs$movePlacePreview(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (PlacePreviewKeys.scrolled(minecraft, yOffset)) {
			ci.cancel();
		}
	}
}
