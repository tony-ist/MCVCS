package tony.mcvcs.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.jspecify.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The texture a picture-in-picture renderer draws into, and its depth, which
 * {@link tony.mcvcs.client.browser.BuildPreviewRenderer} draws its own GPU-resident mesh into instead of submitting
 * geometry every frame.
 */
@Mixin(PictureInPictureRenderer.class)
public interface PictureInPictureRendererAccessor {
	@Accessor("textureView")
	@Nullable GpuTextureView mcvcs$textureView();

	@Accessor("depthTextureView")
	@Nullable GpuTextureView mcvcs$depthTextureView();
}
