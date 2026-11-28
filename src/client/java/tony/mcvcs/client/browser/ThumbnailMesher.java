package tony.mcvcs.client.browser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
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
import tony.mcvcs.client.diff.DiffHighlightRenderer;
import tony.mcvcs.diff.ChangeKind;

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
 * <p>
 * The copy a {@code /vcs place} is showing is meshed here too, see-through, see {@link #meshSeeThrough}.
 */
public final class ThumbnailMesher {
	/** Block light and sky light both at 15. */
	private static final int FULL_BRIGHT = 0xF000F0;
	/** Multiplies a see-through mesh's colors: keeps them as they are and lets a little over half of what is behind show through. */
	private static final int SEE_THROUGH_TINT = ARGB.color(0x70, 0xFF, 0xFF, 0xFF);
	/**
	 * Most vertices a preview may have, over all its layers: about 110 MiB of GPU memory. A grid of at most
	 * {@link tony.mcvcs.build.PreviewGrid#MAX_SIDE} a side stays well under it unless it is dense with blocks whose
	 * faces are never hidden, such as redstone dust, glass panes or leaves; past it, the preview is refused rather
	 * than let one cell take the memory of hundreds.
	 */
	public static final int MAX_VERTICES = 4 * 1024 * 1024;

	/** A version with more geometry than {@link #MAX_VERTICES}. */
	public static final class TooDetailedException extends RuntimeException {
		TooDetailedException() {
			super("Too detailed to preview");
		}
	}

	private ThumbnailMesher() {
	}

	/**
	 * The vertices of one layer, copied out of the builder into memory of their own, which {@link ThumbnailMesh#upload}
	 * or {@link Result#free} releases.
	 *
	 * @param label    what the GPU buffer is called
	 * @param pipeline what it is drawn with
	 * @param textured whether it is block geometry, drawn with the block atlas, rather than plain coloured boxes
	 */
	record Layer(String label, RenderPipeline pipeline, boolean textured, ByteBuffer vertices, int indexCount) {
	}

	/** Opacity of the boxes marking changed blocks, see {@link #mesh(BuildBox, BlockState[], byte[], Context)}. */
	private static final float MARK_ALPHA = 0.55f;
	/** How far the boxes stand out from the blocks they mark, so they are drawn over the blocks' own faces. */
	private static final float MARK_OUTSET = 0.02f;

	/** The layers that have any geometry, solid first and translucent last. */
	public record Result(List<Layer> layers) {
		/** Releases the vertices of a result that will never be uploaded. */
		public void free() {
			for (Layer layer : layers) {
				MemoryUtil.memFree(layer.vertices());
			}
		}
	}

	/** What meshing needs from the client, gathered on the client thread so the meshing itself touches none of it. */
	public record Context(BlockStateModelSet models, BlockColors colors, boolean ambientOcclusion, boolean cutoutLeaves,
				   CardinalLighting lighting, @Nullable Holder<Biome> biome) {
		/** The client's models, colors and options as they are now, tinted for the biome the player stands in. Client thread only. */
		public static Context of(Minecraft client) {
			Holder<Biome> biome = client.level != null && client.player != null ? client.level.getBiome(client.player.blockPosition()) : null;
			CardinalLighting lighting = client.level != null ? client.level.cardinalLighting() : CardinalLighting.DEFAULT;
			return new Context(client.getModelManager().getBlockStateModelSet(), client.getBlockColors(),
				client.options.ambientOcclusion().get(), client.options.cutoutLeaves().get(), lighting, biome);
		}
	}

	/**
	 * Meshes {@code blocks}, which fill {@code extent} in {@link BuildBox} order.
	 *
	 * @throws TooDetailedException if the mesh would have more than {@link #MAX_VERTICES} vertices
	 */
	static Result mesh(BuildBox extent, BlockState[] blocks, Context context) {
		return mesh(extent, blocks, null, context);
	}

	/**
	 * Meshes {@code blocks}, which fill {@code extent} in {@link BuildBox} order, with a translucent box over every
	 * block {@code marks} marks: by the same index, 0 for none, otherwise one more than the {@link ChangeKind} ordinal,
	 * coloured as {@code /vcs changes} colours it. Faces between two boxes of the same kind are left out, so a whole wall
	 * that changed is one tinted wall.
	 *
	 * @throws TooDetailedException if the mesh would have more than {@link #MAX_VERTICES} vertices
	 */
	static Result mesh(BuildBox extent, BlockState[] blocks, byte @Nullable [] marks, Context context) {
		return mesh(extent, blocks, marks, context, false);
	}

	/**
	 * Meshes {@code blocks}, which fill {@code extent} in {@link BuildBox} order, see-through: every face goes into the
	 * translucent layer with its alpha scaled down, so the copy a {@code /vcs place} is showing cannot be mistaken for
	 * blocks already in the world.
	 *
	 * @throws TooDetailedException if the mesh would have more than {@link #MAX_VERTICES} vertices
	 */
	public static Result meshSeeThrough(BuildBox extent, BlockState[] blocks, Context context) {
		return mesh(extent, blocks, null, context, true);
	}

	private static Result mesh(BuildBox extent, BlockState[] blocks, byte @Nullable [] marks, Context context, boolean seeThrough) {
		VersionBlocks level = new VersionBlocks(extent, blocks, context.lighting(), context.biome());
		ModelBlockRenderer renderer = new ModelBlockRenderer(context.ambientOcclusion(), true, context.colors());
		Map<ChunkSectionLayer, ByteBufferBuilder> buffers = new EnumMap<>(ChunkSectionLayer.class);
		Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);

		int[] vertices = {0};
		BlockQuadOutput output = (x, y, z, quad, instance) -> {
			count(vertices);
			instance.setLightCoords(FULL_BRIGHT);
			ChunkSectionLayer layer = quad.materialInfo().layer();
			if (seeThrough) {
				instance.multiplyColor(SEE_THROUGH_TINT);
				layer = ChunkSectionLayer.TRANSLUCENT;
			}
			builder(buffers, builders, layer).putBlockBakedQuad(x, y, z, quad, instance);
		};
		BlockQuadOutput opaqueOutput = seeThrough ? output : (x, y, z, quad, instance) -> {
			count(vertices);
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
			try {
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					BufferBuilder builder = builders.get(layer);
					if (builder != null) {
						copy(builder, "MCVCS preview " + layer.label(), pipeline(layer), true, layers);
					}
				}
				if (marks != null) {
					try (ByteBufferBuilder buffer = new ByteBufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * 4 * 256)) {
						BufferBuilder builder = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
						if (marks(extent, marks, builder, vertices)) {
							// Translucent, so drawn after the blocks: the blocks they cover show through them.
							copy(builder, "MCVCS preview changes", RenderPipelines.DEBUG_FILLED_BOX, false, layers);
						}
					}
				}
			} catch (RuntimeException e) {
				new Result(layers).free();
				throw e;
			}
			return new Result(List.copyOf(layers));
		} finally {
			BlockModelLighter.clearCache();
			buffers.values().forEach(ByteBufferBuilder::close);
		}
	}

	/** Copies what {@code builder} holds, if anything, into memory of its own as one more of {@code layers}. */
	private static void copy(BufferBuilder builder, String label, RenderPipeline pipeline, boolean textured, List<Layer> layers) {
		try (MeshData mesh = builder.build()) {
			if (mesh == null) {
				return;
			}
			ByteBuffer source = mesh.vertexBuffer();
			ByteBuffer copy = MemoryUtil.memAlloc(source.remaining());
			MemoryUtil.memCopy(source, copy);
			layers.add(new Layer(label, pipeline, textured, copy, mesh.drawState().indexCount()));
		}
	}

	/** The pipeline the game draws moving blocks of that layer with: block shading, no chunk offset, no fog of its own. */
	private static RenderPipeline pipeline(ChunkSectionLayer layer) {
		return switch (layer) {
			case SOLID -> RenderPipelines.SOLID_BLOCK;
			case CUTOUT -> RenderPipelines.CUTOUT_BLOCK;
			case TRANSLUCENT -> RenderPipelines.TRANSLUCENT_BLOCK;
		};
	}

	/**
	 * Puts the faces of the boxes over the blocks {@code marks} marks into {@code builder}, wound so that only their
	 * outsides are drawn.
	 *
	 * @return whether there was any
	 */
	private static boolean marks(BuildBox extent, byte[] marks, BufferBuilder builder, int[] vertices) {
		boolean any = false;
		ChangeKind[] kinds = ChangeKind.values();
		for (int index = 0; index < marks.length && index < extent.volume(); index++) {
			byte mark = marks[index];
			if (mark <= 0 || mark > kinds.length) {
				continue;
			}
			int color = ARGB.multiplyAlpha(switch (kinds[mark - 1]) {
				case ADDED -> DiffHighlightRenderer.ADDED_COLOR;
				case REMOVED -> DiffHighlightRenderer.REMOVED_COLOR;
				case CHANGED -> DiffHighlightRenderer.CHANGED_COLOR;
			}, MARK_ALPHA);
			BlockPos at = extent.pos(index);
			int x = at.getX() - extent.min().getX();
			int y = at.getY() - extent.min().getY();
			int z = at.getZ() - extent.min().getZ();
			float x0 = x - MARK_OUTSET, y0 = y - MARK_OUTSET, z0 = z - MARK_OUTSET;
			float x1 = x + 1 + MARK_OUTSET, y1 = y + 1 + MARK_OUTSET, z1 = z + 1 + MARK_OUTSET;
			for (Direction side : Direction.values()) {
				BlockPos next = at.relative(side);
				if (extent.contains(next) && marks[extent.index(next.getX(), next.getY(), next.getZ())] == mark) {
					continue;
				}
				count(vertices);
				any = true;
				// Counter-clockwise seen from outside the box.
				switch (side) {
					case DOWN -> quad(builder, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
					case UP -> quad(builder, color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
					case NORTH -> quad(builder, color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
					case SOUTH -> quad(builder, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
					case WEST -> quad(builder, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
					case EAST -> quad(builder, color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
				}
			}
		}
		return any;
	}

	private static void quad(BufferBuilder builder, int color, float... corners) {
		for (int i = 0; i < corners.length; i += 3) {
			builder.addVertex(corners[i], corners[i + 1], corners[i + 2]).setColor(color);
		}
	}

	/** Counts the four vertices of one more quad, refusing the preview once there are more than {@link #MAX_VERTICES}. */
	private static void count(int[] vertices) {
		vertices[0] += 4;
		if (vertices[0] > MAX_VERTICES) {
			throw new TooDetailedException();
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
