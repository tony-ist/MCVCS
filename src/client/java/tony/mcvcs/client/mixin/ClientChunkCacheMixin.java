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
 * The renderer never meshes a section that is all air in the real world: the section graph leaves out every section
 * {@link ClientChunkCache#addedEmptySections} ever reported, so nothing inside it is drawn, preview blocks included.
 * A preview standing in what is currently empty air would be invisible. {@link PreviewManager} reports the sections
 * it covers as no longer empty when a preview appears; this keeps a chunk reloaded or a block broken while it shows
 * from reporting them empty again. The renderer collects the set once a frame, so this runs once a frame too.
 */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "addedEmptySections", at = @At("RETURN"))
	private void mcvcs$keepPreviewedSections(CallbackInfoReturnable<LongOpenHashSet> cir) {
		PreviewManager.keepPreviewed(level, cir.getReturnValue());
	}
}
