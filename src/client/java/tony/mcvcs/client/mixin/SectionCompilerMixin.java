package tony.mcvcs.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.preview.SeeThroughModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the copy a {@code /vcs place} is showing see-through, so it cannot be mistaken for blocks already in the world.
 * <p>
 * Its blocks are meshed with a {@link SeeThroughModel}. The blocks around it are meshed as if it were air: they would
 * otherwise leave out the faces it covers, and those holes would show through it. For the same reason it never counts
 * as opaque when working out which sections hide others.
 * <p>
 * The see-through model takes effect through the Fabric renderer API, which meshes every block model in place of the
 * vanilla block renderer.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
	@WrapOperation(
		method = "compile",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V")
	)
	private void mcvcs$tesselateBlock(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
									  BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original,
									  @Local(argsOnly = true) RenderSectionRegion region) {
		if (PreviewManager.placeDrawnAt(((RenderSectionRegionAccessor) region).mcvcs$level(), pos)) {
			original.call(renderer, output, x, y, z, level, pos, state, new SeeThroughModel(model), seed);
			return;
		}

		boolean seeingPast = PreviewManager.beginSeeingPastPlace();
		try {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		} finally {
			PreviewManager.endSeeingPastPlace(seeingPast);
		}
	}

	@WrapOperation(
		method = "compile",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V")
	)
	private void mcvcs$tesselateFluid(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState state,
									  FluidState fluid, Operation<Void> original, @Local(argsOnly = true) RenderSectionRegion region) {
		// The copy's own fluids are drawn as they are: water is see-through already.
		boolean seeingPast = !PreviewManager.placeDrawnAt(((RenderSectionRegionAccessor) region).mcvcs$level(), pos)
			&& PreviewManager.beginSeeingPastPlace();
		try {
			original.call(renderer, level, pos, output, state, fluid);
		} finally {
			PreviewManager.endSeeingPastPlace(seeingPast);
		}
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/VisGraph;setOpaque(Lnet/minecraft/core/BlockPos;)V"))
	private void mcvcs$setOpaque(VisGraph graph, BlockPos pos, Operation<Void> original, @Local(argsOnly = true) RenderSectionRegion region) {
		if (!PreviewManager.placeDrawnAt(((RenderSectionRegionAccessor) region).mcvcs$level(), pos)) {
			original.call(graph, pos);
		}
	}
}
