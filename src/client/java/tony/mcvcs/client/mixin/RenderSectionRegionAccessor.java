package tony.mcvcs.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level a section is being meshed from, which {@link SectionCompilerMixin} needs to tell which preview covers a block. */
@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {
	@Accessor("level")
	ClientLevel mcvcs$level();
}
