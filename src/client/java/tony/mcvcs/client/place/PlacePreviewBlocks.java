package tony.mcvcs.client.place;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.PreviewGrid;
import tony.mcvcs.client.browser.ThumbnailMesh;
import tony.mcvcs.client.browser.ThumbnailMesher;
import tony.mcvcs.client.preview.PlacePreview;
import tony.mcvcs.client.preview.PreviewManager;

/**
 * Draws the blocks of the copy a {@code /vcs place} is showing, see-through, where it stands.
 * <p>
 * The copy is meshed once, when it arrives, on a thread of its own, and the mesh stays on the GPU until the copy goes.
 * Moving the copy only changes where the mesh is drawn, so a step costs nothing whatever the copy's size. The mesh is
 * drawn by the mod itself after the world's translucent blocks rather than meshed into the world's chunks, so it shows
 * whichever renderer draws the chunks, Sodium included. Only the copy's nearest surface is drawn, faded over the world,
 * so a big copy does not show all its insides at once, see {@link PlacePreviewPipelines}.
 * <p>
 * A copy with more faces than {@link ThumbnailMesher#MAX_VERTICES} allows is drawn coarser: cubes of 2, 4 and so on
 * blocks a side each drawn as one block, see {@link PreviewGrid}. Every face is lit fully, the world's light plays no
 * part, and fluids and block entities are left out, as in the builds overlay. Only touched from the render thread,
 * which is the client thread, except for the meshing itself.
 */
public final class PlacePreviewBlocks {
	/** Coarsest grid tried before the copy is left to its outline alone. */
	private static final int MAX_SCALE = 64;
	/** Meshes copies one at a time, away from the client thread. */
	private static final ExecutorService MESHER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MCVCS place preview mesher");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * The blocks the mesh is, or is being, made from. The same array however far the copy has moved, see
	 * {@link PlacePreview#movedTo}, so only a new {@code /vcs place} asks for a new mesh.
	 */
	private static BlockState @Nullable [] meshedBlocks;
	private static @Nullable ThumbnailMesh mesh;
	/** Bumped whenever the mesh is dropped, so a mesh finished for a copy that is gone is thrown away. */
	private static volatile int generation;

	private PlacePreviewBlocks() {
	}

	public static void register() {
		// Once the world's translucent blocks are drawn and their render pass is closed, so the mesh can open its own.
		LevelRenderEvents.END_MAIN.register(PlacePreviewBlocks::draw);
		// GPU memory can only be freed on the render thread, which is the client thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (client.isSameThread()) {
				drop();
			} else {
				client.execute(PlacePreviewBlocks::drop);
			}
		});
	}

	/** Whether the blocks of the copy being shown are drawn: its mesh is on the GPU. */
	public static boolean drawn() {
		PlacePreview preview = PreviewManager.place();
		return preview != null && mesh != null && preview.blocks().blocks() == meshedBlocks;
	}

	private static void draw(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		PlacePreview preview = PreviewManager.place();
		follow(client, preview);
		ThumbnailMesh shown = mesh;
		if (preview == null || shown == null || shown.isEmpty() || client.level == null
			|| !preview.blocks().dimension().equals(client.level.dimension())) {
			return;
		}

		// From the camera to the copy's minimum corner, worked out in doubles so a copy far from the world's origin
		// does not wobble; a coarser mesh is scaled back up to the copy's own size.
		CameraRenderState camera = context.levelState().cameraRenderState;
		BlockPos min = preview.box().min();
		Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix)
			.translate((float) (min.getX() - camera.pos.x), (float) (min.getY() - camera.pos.y), (float) (min.getZ() - camera.pos.z))
			.scale(shown.scale());
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		// Depth first, then colour where it is nearest: only the copy's front surface shows, see PlacePreviewPipelines.
		shown.drawInLevel(modelView, target.getColorTextureView(), target.getDepthTextureView(),
			PlacePreviewPipelines.DEPTH, PlacePreviewPipelines.COLOR);
	}

	/** Meshes the copy being shown if it is a new one, and drops the mesh of one that is gone. */
	private static void follow(Minecraft client, @Nullable PlacePreview preview) {
		BlockState[] blocks = preview == null ? null : preview.blocks().blocks();
		if (blocks == meshedBlocks) {
			return;
		}
		drop();
		if (preview == null) {
			return;
		}

		meshedBlocks = blocks;
		BuildBox box = preview.box();
		BuildBox extent = new BuildBox(BlockPos.ZERO, new BlockPos(box.sizeX() - 1, box.sizeY() - 1, box.sizeZ() - 1));
		ThumbnailMesher.Context context = ThumbnailMesher.Context.of(client);
		int started = generation;
		String name = preview.blocks().name();
		MESHER.execute(() -> {
			Meshed meshed;
			try {
				meshed = mesh(extent, blocks, context, started);
			} catch (RuntimeException e) {
				MCVCS.LOGGER.error("Failed to mesh the copy of '{}' being placed", name, e);
				return;
			}
			if (meshed == null) {
				return;
			}
			client.execute(() -> {
				if (generation != started) {
					meshed.result().free();
					return;
				}
				mesh = ThumbnailMesh.upload(meshed.result(), meshed.grid(), meshed.scale());
			});
		});
	}

	/** A mesh made of the blocks of {@code grid}, each of which stands for {@code scale} blocks of the copy a side. */
	private record Meshed(ThumbnailMesher.Result result, BuildBox grid, int scale) {
	}

	/**
	 * Meshes {@code blocks}, which fill {@code extent}, as finely as {@link ThumbnailMesher#MAX_VERTICES} allows; null
	 * if not even {@link #MAX_SCALE} is coarse enough, or the copy went while it was being meshed.
	 */
	private static @Nullable Meshed mesh(BuildBox extent, BlockState[] blocks, ThumbnailMesher.Context context, int started) {
		for (int scale = 1; scale <= MAX_SCALE; scale *= 2) {
			if (generation != started) {
				return null;
			}
			BuildBox grid = scale == 1 ? extent : PreviewGrid.grid(extent, scale);
			BlockState[] cells = scale == 1 ? blocks
				: PreviewGrid.sample(new BoxSnapshot(extent, blocks, Int2ObjectMaps.emptyMap()), extent, scale).blocks();
			try {
				return new Meshed(ThumbnailMesher.meshSeeThrough(grid, cells, context), grid, scale);
			} catch (ThumbnailMesher.TooDetailedException e) {
				MCVCS.LOGGER.info("The copy being placed has more than {} vertices at 1:{}, drawing it coarser", ThumbnailMesher.MAX_VERTICES, scale);
			}
		}
		MCVCS.LOGGER.warn("The copy being placed is too detailed to draw even at 1:{}; only its outline is shown", MAX_SCALE);
		return null;
	}

	/** Frees the mesh and forgets what it was made from, throwing away one still being made. */
	private static void drop() {
		generation++;
		meshedBlocks = null;
		if (mesh != null) {
			mesh.close();
			mesh = null;
		}
	}
}
