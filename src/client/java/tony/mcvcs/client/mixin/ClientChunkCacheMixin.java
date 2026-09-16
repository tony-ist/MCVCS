package tony.mcvcs.client.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import tony.mcvcs.client.preview.PreviewManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the renderer from skipping the chunk sections a preview draws blocks in.
 * <p>
 * The renderer never meshes a section that is all air in the real world: {@link ClientChunkCache#getLoadedEmptySections}
 * lists those, and the section graph leaves every listed section out, so nothing inside it is ever drawn, preview
 * blocks included. A preview standing in what is currently empty air would be invisible. The renderer asks for the
 * list every frame, so answering it without the sections a preview covers is enough to have them meshed like any
 * other section.
 */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "getLoadedEmptySections", at = @At("RETURN"), cancellable = true)
	private void mcvcs$keepPreviewedSections(CallbackInfoReturnable<LongOpenHashSet> cir) {
		LongOpenHashSet emptySections = cir.getReturnValue();
		LongOpenHashSet withoutPreviewed = PreviewManager.withoutPreviewed(level, emptySections);
		if (withoutPreviewed != emptySections) {
			cir.setReturnValue(withoutPreviewed);
		}
	}
}
