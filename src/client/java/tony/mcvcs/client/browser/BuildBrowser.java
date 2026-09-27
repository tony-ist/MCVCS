package tony.mcvcs.client.browser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.network.BuildListPayload;
import tony.mcvcs.network.BuildListRequestPayload;
import tony.mcvcs.network.BuildSummary;
import tony.mcvcs.network.ThumbnailBeginPayload;
import tony.mcvcs.network.ThumbnailBlocksPayload;
import tony.mcvcs.network.ThumbnailFailedPayload;
import tony.mcvcs.network.ThumbnailRequestPayload;

/**
 * The client side of the builds overlay: the list of builds the server last sent, and the preview of every version
 * asked for since joining.
 * <p>
 * The list is only asked for when the overlay opens, when its refresh button is pressed, and after the overlay has
 * deleted a build; nothing is pushed while it is open. Previews are asked for as the list arrives, for every build no bigger than
 * {@link ClientConfig.Settings#autoDownloadLimit}, and for a bigger one when its cell is clicked. A version never
 * changes once committed, so a preview is asked for once and kept until the client leaves the server; a refreshed
 * list that names a newer version gets a new preview for it, and one that failed is asked for again.
 * <p>
 * Blocks arrive on the client thread; the mesh is built on a thread of its own, one preview at a time, and put on the
 * GPU back on the client thread.
 */
public final class BuildBrowser {
	/** Where the list of builds is. */
	public enum ListState {
		/** Not asked for since joining. */
		NONE,
		/** Asked for; waiting for the server. */
		LOADING,
		/** Arrived; see {@link #builds()}. */
		LOADED,
		/** The server said the player may not see the builds. */
		DENIED
	}

	/** Builds preview meshes one at a time, away from the client thread. */
	private static final ExecutorService MESHER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MCVCS preview mesher");
		thread.setDaemon(true);
		return thread;
	});

	private static ListState listState = ListState.NONE;
	private static List<BuildSummary> builds = List.of();
	/** Every preview asked for since joining, in the order they were asked for. */
	private static final Map<Thumbnail.Key, Thumbnail> THUMBNAILS = new LinkedHashMap<>();
	/** Bumped whenever the previews are dropped, so a mesh finished for an earlier session is thrown away. */
	private static int generation;
	/** Whether the list is to be asked for again when the placements next arrive, see {@link #refreshAfterCommand}. */
	private static boolean refreshOnSync;

	private BuildBrowser() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BuildListPayload.TYPE, (payload, context) -> list(payload));
		ClientPlayNetworking.registerGlobalReceiver(ThumbnailBeginPayload.TYPE, (payload, context) -> {
			Thumbnail thumbnail = inFlight(payload.build(), payload.version());
			if (thumbnail != null) {
				thumbnail.begin(payload.extent(), payload.scale());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(ThumbnailBlocksPayload.TYPE, (payload, context) -> blocks(payload));
		ClientPlacements.onUpdate(() -> {
			if (refreshOnSync) {
				refreshOnSync = false;
				refresh();
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(ThumbnailFailedPayload.TYPE, (payload, context) -> {
			Thumbnail thumbnail = inFlight(payload.build(), payload.version());
			if (thumbnail != null) {
				thumbnail.fail(payload.reason(), true);
			}
		});
		// Previews belong to the server they came from; the next one may have other builds under the same names.
		// GPU memory can only be freed on the render thread, which is the client thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (client.isSameThread()) {
				forget();
			} else {
				client.execute(BuildBrowser::forget);
			}
		});
	}

	/** Whether the server has this mod, and one new enough to answer the overlay. */
	public static boolean serverSupported() {
		return ClientPlayNetworking.canSend(BuildListRequestPayload.TYPE);
	}

	public static ListState listState() {
		return listState;
	}

	/** Every build the server last listed, sorted by name. */
	public static List<BuildSummary> builds() {
		return builds;
	}

	/**
	 * Asks the server for the builds again; previews that failed to arrive are asked for again when the list arrives,
	 * but not those of versions too detailed to preview, which would only fail again.
	 */
	public static void refresh() {
		THUMBNAILS.values().removeIf(thumbnail -> thumbnail.state() == Thumbnail.State.FAILED && thumbnail.retryable());
		listState = ListState.LOADING;
		ClientPlayNetworking.send(BuildListRequestPayload.INSTANCE);
	}

	/**
	 * Asks the server for the builds again once it next sends the placements, which it does after a command changes a
	 * build, for a command sent just before. Asking straight away could be answered before the command has run: the
	 * server takes commands and the overlay's requests from separate queues. A command that fails and changes nothing
	 * may send nothing, and then the list stays as it is, which is still right.
	 */
	public static void refreshAfterCommand() {
		refreshOnSync = true;
	}

	/** The preview of the version {@code build} names, or null if it has not been asked for. */
	public static @Nullable Thumbnail thumbnail(BuildSummary build) {
		return THUMBNAILS.get(key(build));
	}

	/** Whether {@code build} is small enough for its preview to be downloaded without being clicked. */
	public static boolean downloadsByItself(BuildSummary build) {
		return build.extent().volume() <= ClientConfig.settings().autoDownloadLimit();
	}

	/** Asks the server for the preview of the version {@code build} names, unless it has been asked for already. */
	public static void download(BuildSummary build) {
		Thumbnail.Key key = key(build);
		Thumbnail existing = THUMBNAILS.get(key);
		if (existing != null && existing.state() != Thumbnail.State.FAILED) {
			return;
		}
		THUMBNAILS.put(key, new Thumbnail(key));
		ClientPlayNetworking.send(new ThumbnailRequestPayload(build.name(), build.version()));
	}

	private static Thumbnail.Key key(BuildSummary build) {
		return new Thumbnail.Key(build.name(), build.version(), build.extent());
	}

	private static void list(BuildListPayload payload) {
		if (!payload.allowed()) {
			listState = ListState.DENIED;
			builds = List.of();
			return;
		}
		listState = ListState.LOADED;
		builds = payload.builds();
		for (BuildSummary build : builds) {
			if (downloadsByItself(build)) {
				download(build);
			}
		}
	}

	/** The preview being sent for version {@code version} of {@code build}, if it is still wanted. */
	private static @Nullable Thumbnail inFlight(String build, int version) {
		for (Thumbnail thumbnail : THUMBNAILS.values()) {
			Thumbnail.Key key = thumbnail.key();
			Thumbnail.State state = thumbnail.state();
			if (key.build().equals(build) && key.version() == version && (state == Thumbnail.State.REQUESTED || state == Thumbnail.State.DOWNLOADING)) {
				return thumbnail;
			}
		}
		return null;
	}

	private static void blocks(ThumbnailBlocksPayload payload) {
		Thumbnail thumbnail = inFlight(payload.build(), payload.version());
		if (thumbnail == null) {
			return;
		}
		BlockState[] blocks = thumbnail.accept(payload);
		if (blocks != null) {
			mesh(thumbnail, blocks);
		}
	}

	/** Builds the mesh of {@code thumbnail} from its {@code blocks} on {@link #MESHER} and uploads it on the client thread. */
	private static void mesh(Thumbnail thumbnail, BlockState[] blocks) {
		Minecraft client = Minecraft.getInstance();
		Holder<Biome> biome = client.level != null && client.player != null ? client.level.getBiome(client.player.blockPosition()) : null;
		CardinalLighting lighting = client.level != null ? client.level.cardinalLighting() : CardinalLighting.DEFAULT;
		ThumbnailMesher.Context context = new ThumbnailMesher.Context(
			client.getModelManager().getBlockStateModelSet(), client.getBlockColors(),
			client.options.ambientOcclusion().get(), client.options.cutoutLeaves().get(), lighting, biome);
		int started = generation;
		BuildBox grid = thumbnail.grid();
		int scale = thumbnail.scale();
		if (grid == null) {
			thumbnail.fail("Malformed preview", true);
			return;
		}

		MESHER.execute(() -> {
			ThumbnailMesher.Result result;
			try {
				result = ThumbnailMesher.mesh(grid, blocks, context);
			} catch (ThumbnailMesher.TooDetailedException e) {
				MCVCS.LOGGER.warn("Not previewing '{}' v{}: more than {} vertices", thumbnail.key().build(), thumbnail.key().version(), ThumbnailMesher.MAX_VERTICES);
				client.execute(() -> thumbnail.fail(e.getMessage(), false));
				return;
			} catch (RuntimeException e) {
				MCVCS.LOGGER.error("Failed to build the preview of '{}' v{}", thumbnail.key().build(), thumbnail.key().version(), e);
				client.execute(() -> thumbnail.fail("Failed to build preview", true));
				return;
			}
			client.execute(() -> {
				if (generation != started || THUMBNAILS.get(thumbnail.key()) != thumbnail) {
					result.free();
					return;
				}
				thumbnail.ready(ThumbnailMesh.upload(result, grid, scale));
			});
		});
	}

	/** Drops the list and every preview, freeing their GPU memory. */
	private static void forget() {
		generation++;
		THUMBNAILS.values().forEach(Thumbnail::close);
		THUMBNAILS.clear();
		builds = List.of();
		listState = ListState.NONE;
		refreshOnSync = false;
	}
}
