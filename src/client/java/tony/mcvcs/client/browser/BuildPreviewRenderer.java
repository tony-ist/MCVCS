package tony.mcvcs.client.browser;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a {@link BuildPreviewRenderState} into the texture the GUI then shows in the build's cell. Registered with
 * Fabric, which gives every preview on the screen a renderer of its own, so each cell keeps its own texture.
 * <p>
 * The camera looks down on the build at {@link #TILT} degrees through a perspective of {@link #FOV} degrees, from just
 * far enough that the sphere around the build fits the cell: whichever way it has turned, no corner of it is cut off.
 * The build turns about the vertical axis through its centre.
 */
public final class BuildPreviewRenderer extends PictureInPictureRenderer<BuildPreviewRenderState> {
	/** Vertical field of view, in degrees: narrow, so the build looks much as it does from across a room. */
	public static final float FOV = 30.0f;
	/** How far the camera looks down on the build, in degrees. */
	public static final float TILT = 30.0f;
	/** Room left around the build's sphere, as a share of its radius. */
	private static final float MARGIN = 0.05f;

	private final Projection projection = new Projection();
	private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("MCVCS build preview");
	/** What the texture shows now, so a preview that has not turned since is not drawn again. */
	private @Nullable BuildPreviewRenderState drawn;

	public BuildPreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	@Override
	public Class<BuildPreviewRenderState> getRenderStateClass() {
		return BuildPreviewRenderState.class;
	}

	@Override
	protected boolean textureIsReadyToBlit(BuildPreviewRenderState state) {
		return state.equals(drawn);
	}

	@Override
	protected void renderToTexture(BuildPreviewRenderState state, PoseStack poseStack) {
		GpuTextureView color = RenderSystem.outputColorTextureOverride;
		if (color == null) {
			return;
		}

		ThumbnailMesh mesh = state.mesh();
		Vector3f size = mesh.size();
		float radius = size.length() / 2.0f * (1.0f + MARGIN);
		float width = state.x1() - state.x0();
		float height = state.y1() - state.y0();
		// The narrower of the two fields of view decides how far back the camera has to stand.
		float halfFov = (float) Math.toRadians(FOV / 2.0f);
		if (width < height) {
			halfFov = (float) Math.atan(Math.tan(halfFov) * width / height);
		}
		float distance = radius / (float) Math.sin(halfFov);
		projection.setupPerspective(Math.max(0.05f, distance - radius - 1.0f), distance + radius + 1.0f, FOV, width, height);

		Matrix4f modelView = new Matrix4f()
			.translate(0.0f, 0.0f, -distance)
			.rotateX((float) Math.toRadians(TILT))
			.rotateY((float) Math.toRadians(state.yaw()))
			.translate(-size.x / 2.0f, -size.y / 2.0f, -size.z / 2.0f);
		mesh.draw(modelView, projectionBuffer.getBuffer(projection), color, RenderSystem.outputDepthTextureOverride);
		drawn = state;
	}

	@Override
	protected String getTextureLabel() {
		return "mcvcs build preview";
	}

	@Override
	public void close() {
		super.close();
		projectionBuffer.close();
	}
}
