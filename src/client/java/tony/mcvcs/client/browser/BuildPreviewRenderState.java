package tony.mcvcs.client.browser;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

/**
 * One build's turning preview in the builds overlay, as the GUI hands it to {@link BuildPreviewRenderer}: which mesh,
 * turned how far, and where on the screen.
 *
 * @param mesh        the version to draw
 * @param yaw         how far the build has turned about its vertical axis, in degrees
 * @param x0          left edge on the screen, in GUI pixels
 * @param y0          top edge
 * @param x1          right edge
 * @param y1          bottom edge
 * @param scissorArea what the grid shows of it while it is scrolled partly out of view
 * @param bounds      the part of it that is on the screen
 */
public record BuildPreviewRenderState(ThumbnailMesh mesh, float yaw, int x0, int y0, int x1, int y1,
									  @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
	public BuildPreviewRenderState(ThumbnailMesh mesh, float yaw, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea) {
		this(mesh, yaw, x0, y0, x1, y1, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
	}

	@Override
	public float scale() {
		return 1.0f;
	}
}
