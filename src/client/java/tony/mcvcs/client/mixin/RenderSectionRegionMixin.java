package tony.mcvcs.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import tony.mcvcs.client.preview.PreviewManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the chunk mesher preview blocks instead of real ones inside the preview box.
 * <p>
 * {@link RenderSectionRegion} is the snapshot of the world a section is meshed from; every block, fluid, face-culling
 * and ambient-occlusion lookup during meshing goes through these two getters. Overriding them is enough to make the
 * built geometry show the preview while the actual world stays untouched. Block entities are not substituted, so
 * previewed chests, signs and the like render only their static model part.
 */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void mcvcs$previewBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		BlockState preview = PreviewManager.substitute(level, pos);
		if (preview != null) {
			cir.setReturnValue(preview);
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void mcvcs$previewFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		BlockState preview = PreviewManager.substitute(level, pos);
		if (preview != null) {
			cir.setReturnValue(preview.getFluidState());
		}
	}
}
