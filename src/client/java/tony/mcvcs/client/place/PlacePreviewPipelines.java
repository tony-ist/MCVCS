package tony.mcvcs.client.place;

import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
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
 * Both are the game's own {@link RenderPipelines#TRANSLUCENT_BLOCK} with only the depth and colour settings changed, so
 * the shaders, vertex format and texture cut-out are exactly those of the faces they are drawn after.
 */
public final class PlacePreviewPipelines {
	/** The copy's depth alone, nearest face winning; no colour is written. */
	public static final RenderPipeline DEPTH = copyOfTranslucentBlock("pipeline/place_preview_depth",
		new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE),
		new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true));
	/** The copy's colour, blended, only where a face is the one {@link #DEPTH} left nearest; depth is left as it is. */
	public static final RenderPipeline COLOR = copyOfTranslucentBlock("pipeline/place_preview_color",
		new ColorTargetState(BlendFunction.TRANSLUCENT),
		new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false));

	private PlacePreviewPipelines() {
	}

	/**
	 * {@link RenderPipelines#TRANSLUCENT_BLOCK} under the location {@code path} of this mod, drawing into
	 * {@code color} and testing and writing depth as {@code depth} says.
	 */
	private static RenderPipeline copyOfTranslucentBlock(String path, ColorTargetState color, DepthStencilState depth) {
		RenderPipeline source = RenderPipelines.TRANSLUCENT_BLOCK;
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(MCVCS.MOD_ID, path))
			.withVertexShader(source.getVertexShader())
			.withFragmentShader(source.getFragmentShader())
			.withVertexFormat(source.getVertexFormat(), source.getVertexFormatMode())
			.withCull(source.isCull())
			.withPolygonMode(source.getPolygonMode())
			.withColorTargetState(color)
			.withDepthStencilState(depth);
		source.getShaderDefines().flags().forEach(builder::withShaderDefine);
		for (Map.Entry<String, String> define : source.getShaderDefines().values().entrySet()) {
			define(builder, define.getKey(), define.getValue());
		}
		source.getSamplers().forEach(builder::withSampler);
		for (RenderPipeline.UniformDescription uniform : source.getUniforms()) {
			if (uniform.textureFormat() == null) {
				builder.withUniform(uniform.name(), uniform.type());
			} else {
				builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
			}
		}
		return builder.build();
	}

	/** Copies a valued shader define, which the builder only takes as a number: an int where it is one, else a float. */
	private static void define(RenderPipeline.Builder builder, String name, String value) {
		try {
			builder.withShaderDefine(name, Integer.parseInt(value));
		} catch (NumberFormatException notInt) {
			builder.withShaderDefine(name, Float.parseFloat(value));
		}
	}
}
