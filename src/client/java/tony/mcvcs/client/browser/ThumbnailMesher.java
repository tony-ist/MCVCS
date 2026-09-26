package tony.mcvcs.client.browser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import tony.mcvcs.build.BuildBox;

/**
 * Turns the blocks of a version into the vertices of its preview, the same way the game meshes a chunk section: each
 * block's model is tessellated with faces between neighbouring solid blocks left out, shaded by face direction and
 * ambient occlusion, and sorted into the solid, cutout and translucent layers. Only block models are drawn; block
 * entities such as chests and signs, and fluids, are left out.
 * <p>
 * Every block is lit as if in full daylight, so a preview looks the same whatever time it is and wherever the build
 * stands; the world's light plays no part. Grass, leaves and the like take their colour from the biome the player is
 * standing in. Positions are in the version's own coordinates, with its minimum corner at the origin.
 * <p>
 * Nothing here touches the level or the GPU, so it can run on any thread; {@link ThumbnailMesh#upload} then puts the
 * result on the GPU from the render thread.
 */
final class ThumbnailMesher {
	/** Block light and sky light both at 15. */
	private static final int FULL_BRIGHT = 0xF000F0;

	private ThumbnailMesher() {
	}

	/**
	 * The vertices of one layer, copied out of the builder into memory of their own, which {@link ThumbnailMesh#upload}
	 * or {@link Result#free} releases.
	 */
	record Layer(ChunkSectionLayer layer, ByteBuffer vertices, int indexCount) {
	}

	/** The layers that have any geometry, solid first and translucent last. */
	record Result(List<Layer> layers) {
		/** Releases the vertices of a result that will never be uploaded. */
		void free() {
			for (Layer layer : layers) {
				MemoryUtil.memFree(layer.vertices());
			}
		}
	}

	/** What meshing needs from the client, gathered on the client thread so the meshing itself touches none of it. */
	record Context(BlockStateModelSet models, BlockColors colors, boolean ambientOcclusion, boolean cutoutLeaves,
				   CardinalLighting lighting, @Nullable Holder<Biome> biome) {
	}

	/**
	 * Meshes {@code blocks}, which fill {@code extent} in {@link BuildBox} order.
	 */
	static Result mesh(BuildBox extent, BlockState[] blocks, Context context) {
		VersionBlocks level = new VersionBlocks(extent, blocks, context.lighting(), context.biome());
		ModelBlockRenderer renderer = new ModelBlockRenderer(context.ambientOcclusion(), true, context.colors());
		Map<ChunkSectionLayer, ByteBufferBuilder> buffers = new EnumMap<>(ChunkSectionLayer.class);
		Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);

		BlockQuadOutput output = (x, y, z, quad, instance) -> {
			instance.setLightCoords(FULL_BRIGHT);
			builder(buffers, builders, quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
		};
		BlockQuadOutput opaqueOutput = (x, y, z, quad, instance) -> {
			instance.setLightCoords(FULL_BRIGHT);
			builder(buffers, builders, ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);
		};

		BlockModelLighter.enableCaching();
		try {
			BlockPos min = extent.min();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int index = 0; index < blocks.length; index++) {
				BlockState state = blocks[index];
				if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
					continue;
				}
				BlockPos at = extent.pos(index);
				pos.set(at);
				renderer.tesselateBlock(
					ModelBlockRenderer.forceOpaque(context.cutoutLeaves(), state) ? opaqueOutput : output,
					at.getX() - min.getX(), at.getY() - min.getY(), at.getZ() - min.getZ(),
					level, pos, state, context.models().get(state), state.getSeed(pos)
				);
			}

			List<Layer> layers = new ArrayList<>();
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				BufferBuilder builder = builders.get(layer);
				if (builder == null) {
					continue;
				}
				try (MeshData mesh = builder.build()) {
					if (mesh == null) {
						continue;
					}
					ByteBuffer source = mesh.vertexBuffer();
					ByteBuffer copy = MemoryUtil.memAlloc(source.remaining());
					MemoryUtil.memCopy(source, copy);
					layers.add(new Layer(layer, copy, mesh.drawState().indexCount()));
				}
			}
			return new Result(List.copyOf(layers));
		} finally {
			BlockModelLighter.clearCache();
			buffers.values().forEach(ByteBufferBuilder::close);
		}
	}

	private static BufferBuilder builder(Map<ChunkSectionLayer, ByteBufferBuilder> buffers, Map<ChunkSectionLayer, BufferBuilder> builders, ChunkSectionLayer layer) {
		BufferBuilder builder = builders.get(layer);
		if (builder == null) {
			ByteBufferBuilder buffer = new ByteBufferBuilder(layer.vertexFormat().getVertexSize() * 4 * 256);
			buffers.put(layer, buffer);
			builder = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, layer.vertexFormat());
			builders.put(layer, builder);
		}
		return builder;
	}

	/**
	 * The version's blocks as the block renderer looks them up, at their world positions inside {@code extent}: air
	 * all around them, no block entities and no light of its own, since every face is lit fully anyway.
	 */
	private record VersionBlocks(BuildBox extent, BlockState[] blocks, CardinalLighting lighting, @Nullable Holder<Biome> biome) implements BlockAndTintGetter {
		@Override
		public BlockState getBlockState(BlockPos pos) {
			return extent.contains(pos) ? blocks[extent.index(pos.getX(), pos.getY(), pos.getZ())] : Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public CardinalLighting cardinalLighting() {
			return lighting;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			return LevelLightEngine.EMPTY;
		}

		@Override
		public int getBlockTint(BlockPos pos, ColorResolver resolver) {
			return biome == null ? -1 : resolver.getColor(biome.value(), pos.getX(), pos.getZ());
		}

		@Override
		public int getHeight() {
			return extent.sizeY() + 2;
		}

		@Override
		public int getMinY() {
			return extent.min().getY() - 1;
		}
	}
}
