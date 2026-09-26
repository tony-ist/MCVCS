package tony.mcvcs.client.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.BuildBox;

/**
 * A version's preview on the GPU: the vertices {@link ThumbnailMesher} made, uploaded once and drawn every frame with
 * whatever turn the overlay gives it. Drawing only sets a new transform, so a build of any size costs the CPU nothing
 * per frame; its vertices never leave the GPU.
 * <p>
 * Lives until the client leaves the server, see {@link BuildBrowser}; only ever touched from the render thread.
 */
public final class ThumbnailMesh implements AutoCloseable {
	private record Layer(RenderPipeline pipeline, GpuBuffer vertices, int indexCount) {
	}

	private final List<Layer> layers;
	/** Width, height and depth of the version in blocks; its vertices run from the origin to here. */
	private final Vector3f size;
	private boolean closed;

	private ThumbnailMesh(List<Layer> layers, Vector3f size) {
		this.layers = layers;
		this.size = size;
	}

	/** Puts {@code result}, the version of extent {@code extent}, on the GPU and frees the memory it was built in. */
	static ThumbnailMesh upload(ThumbnailMesher.Result result, BuildBox extent) {
		GpuDevice device = RenderSystem.getDevice();
		List<Layer> layers = new ArrayList<>();
		try {
			for (ThumbnailMesher.Layer layer : result.layers()) {
				GpuBuffer vertices = device.createBuffer(() -> "MCVCS preview " + layer.layer().label(), GpuBuffer.USAGE_VERTEX, layer.vertices());
				layers.add(new Layer(pipeline(layer), vertices, layer.indexCount()));
			}
		} finally {
			result.free();
		}
		return new ThumbnailMesh(List.copyOf(layers), new Vector3f(extent.sizeX(), extent.sizeY(), extent.sizeZ()));
	}

	/** The pipeline the game draws moving blocks of that layer with: block shading, no chunk offset, no fog of its own. */
	private static RenderPipeline pipeline(ThumbnailMesher.Layer layer) {
		return switch (layer.layer()) {
			case SOLID -> RenderPipelines.SOLID_BLOCK;
			case CUTOUT -> RenderPipelines.CUTOUT_BLOCK;
			case TRANSLUCENT -> RenderPipelines.TRANSLUCENT_BLOCK;
		};
	}

	/** Whether there is anything to draw: false for a version of nothing but air or blocks without a model. */
	public boolean isEmpty() {
		return layers.isEmpty();
	}

	/** The version's size in blocks along x, y and z. */
	public Vector3f size() {
		return new Vector3f(size);
	}

	/**
	 * Draws the version into {@code color} and {@code depth} through {@code projection}, placed by {@code modelView}.
	 * Solid geometry goes first and translucent last, which is all the ordering a small preview needs.
	 */
	// The block atlas is looked up by the same deprecated id the game's own moving blocks are drawn with.
	@SuppressWarnings("deprecation")
	void draw(Matrix4fc modelView, GpuBufferSlice projection, GpuTextureView color, @Nullable GpuTextureView depth) {
		if (closed || layers.isEmpty()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
			.writeTransform(modelView, new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), new Vector3f(), new Matrix4f());
		GpuTextureView atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		// The sampler the game draws moving blocks with: mipmapped when minified, crisp pixels when magnified.
		GpuSampler atlasSampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, true);
		GpuSampler lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);

		for (Layer layer : layers) {
			GpuBuffer indices = quadIndices.getBuffer(layer.indexCount());
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "MCVCS build preview", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
				pass.setPipeline(layer.pipeline());
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("Projection", projection);
				pass.setUniform("DynamicTransforms", transforms);
				pass.bindTexture("Sampler0", atlas, atlasSampler);
				pass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(), lightmapSampler);
				pass.setVertexBuffer(0, layer.vertices());
				pass.setIndexBuffer(indices, quadIndices.type());
				pass.drawIndexed(0, 0, layer.indexCount(), 1);
			}
		}
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			layers.forEach(layer -> layer.vertices().close());
		}
	}
}
