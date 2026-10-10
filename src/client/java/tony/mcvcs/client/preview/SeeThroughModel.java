package tony.mcvcs.client.preview;

import java.util.List;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A block model drawn see-through: every quad goes into the translucent layer with its alpha scaled down, so the copy a
 * {@code /vcs place} is showing cannot be mistaken for blocks already in the world. See
 * {@link tony.mcvcs.client.mixin.SectionCompilerMixin}.
 *
 * @param model the block's own model
 */
public record SeeThroughModel(BlockStateModel model) implements BlockStateModel {
	/** Multiplies the copy's colors: keeps them as they are and lets a little over half of what is behind show through. */
	private static final int TINT = ARGB.color(0x70, 0xFF, 0xFF, 0xFF);
	private static final QuadTransform SEE_THROUGH = quad -> {
		quad.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
		for (int i = 0; i < 4; i++) {
			quad.color(i, ARGB.multiply(quad.color(i), TINT));
		}
		return true;
	};

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
		// Transforms pushed later run first, so this one sees each quad after anything the model itself does to it.
		emitter.pushTransform(SEE_THROUGH);
		try {
			model.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			emitter.popTransform();
		}
	}

	@Override
	public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
		model.collectParts(random, output);
	}

	@Override
	public Material.Baked particleMaterial() {
		return model.particleMaterial();
	}

	@Override
	@BakedQuad.MaterialFlags
	public int materialFlags() {
		return model.materialFlags() | BakedQuad.FLAG_TRANSLUCENT;
	}
}
