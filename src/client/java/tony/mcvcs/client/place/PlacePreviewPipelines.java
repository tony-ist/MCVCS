package tony.mcvcs.client.place;

import java.util.Optional;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import tony.mcvcs.MCVCS;

/**
 * The two passes the copy a {@code /vcs place} is showing is drawn in, so that only its nearest surface shows, faded
 * over the world, rather than every face of it blended over the others.
 * <p>
 * {@link #DEPTH} draws the copy into the depth buffer alone, which leaves there how far the nearest face of the copy is
 * at every pixel. {@link #COLOR} then draws it again, blended, wherever a face is no further than that: the nearest
 * face passes and every face behind it fails. However big the copy, it looks like one faded solid thing, the same from
 * every side, and the order its faces are drawn in no longer matters.
 * <p>
 * Both are built the way the game builds its own {@link RenderPipelines#TRANSLUCENT_BLOCK}, with only the depth and
 * colour settings changed, so the shaders, vertex format and texture cut-out are exactly those of the faces they are
 * drawn after. Depth is reversed, nearer is greater, so the tests keep a face that is at least as near.
 */
public final class PlacePreviewPipelines {
	/** The copy's depth alone, nearest face winning; no colour is written. */
	public static final RenderPipeline DEPTH = translucentBlock("pipeline/place_preview_depth",
		new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE),
		new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true));
	/** The copy's colour, blended, only where a face is the one {@link #DEPTH} left nearest; depth is left as it is. */
	public static final RenderPipeline COLOR = translucentBlock("pipeline/place_preview_color",
		new ColorTargetState(BlendFunction.TRANSLUCENT),
		new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false));

	private PlacePreviewPipelines() {
	}

	/**
	 * {@link RenderPipelines#TRANSLUCENT_BLOCK} under the location {@code path} of this mod, drawing into
	 * {@code color} and testing and writing depth as {@code depth} says.
	 */
	private static RenderPipeline translucentBlock(String path, ColorTargetState color, DepthStencilState depth) {
		return RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(MCVCS.MOD_ID, path))
			.withShaderDefine("ALPHA_CUTOUT", 0.1F)
			.withColorTargetState(color)
			.withDepthStencilState(depth)
			.build();
	}
}
