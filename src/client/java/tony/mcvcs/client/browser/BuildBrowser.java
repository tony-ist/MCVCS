package tony.mcvcs.client.browser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.network.BuildListPayload;
import tony.mcvcs.network.BuildListRequestPayload;
import tony.mcvcs.network.BuildSummary;
import tony.mcvcs.network.CommitChangesPayload;
import tony.mcvcs.network.CommitPreviewPayload;
import tony.mcvcs.network.CommitPreviewRequestPayload;
import tony.mcvcs.network.PlacementStatusPayload;
import tony.mcvcs.network.PlacementStatusRequestPayload;
import tony.mcvcs.network.ThumbnailBeginPayload;
import tony.mcvcs.network.ThumbnailBlocksPayload;
import tony.mcvcs.network.ThumbnailFailedPayload;
import tony.mcvcs.network.ThumbnailRequestPayload;

/**
 * The client side of the builds overlay: the list of builds the server last sent, and the preview of every version
 * asked for since joining.
 * <p>
 * The list is only asked for when the overlay opens and after the overlay has deleted a build; nothing is pushed while
 * it is open. Previews are asked for as the list arrives, for every build no bigger than
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
	private static @Nullable PlacementStatusPayload status;
	private static @Nullable CommitPreview commitPreview;

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
		ClientPlayNetworking.registerGlobalReceiver(PlacementStatusPayload.TYPE, (payload, context) -> status = payload);
		ClientPlayNetworking.registerGlobalReceiver(CommitPreviewPayload.TYPE, (payload, context) -> {
			CommitPreview preview = commitPreview;
			if (preview == null) {
				return;
			}
			if (payload.build().isEmpty()) {
				preview.thumbnail().fail("Nothing selected", false);
			} else if (payload.build().equals(preview.placement().build())) {
				preview.summarise(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(CommitChangesPayload.TYPE, (payload, context) -> {
			CommitPreview preview = commitPreview;
			if (preview != null && payload.build().equals(preview.placement().build())) {
				preview.mark(payload);
			}
		});
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

	/** Asks the server how many blocks of the selected placement are modified; {@link #status()} is null until it answers. */
	public static void requestStatus() {
		status = null;
		ClientPlayNetworking.send(PlacementStatusRequestPayload.INSTANCE);
	}

	/** The server's last answer to {@link #requestStatus()}, or null while it is awaited. */
	public static @Nullable PlacementStatusPayload status() {
		return status;
	}

	/**
	 * Asks the server what committing {@code placement}, the selected one, would save, dropping what it said last time;
	 * see {@link #commitPreview()}.
	 */
	public static void requestCommitPreview(ClientPlacement placement) {
		requestCommitPreview(placement, true);
	}

	/** {@link #requestCommitPreview(ClientPlacement)}, with the changed blocks tinted in the preview only if {@code marked}. */
	public static void requestCommitPreview(ClientPlacement placement, boolean marked) {
		dropCommitPreview();
		commitPreview = new CommitPreview(placement, marked);
		ClientPlayNetworking.send(CommitPreviewRequestPayload.INSTANCE);
	}

	/** What committing the selected placement would save, since {@link #requestCommitPreview} last asked; null if dropped. */
	public static @Nullable CommitPreview commitPreview() {
		return commitPreview;
	}

	/** Forgets the commit preview, freeing its GPU memory; the commit window does this when it closes. */
	public static void dropCommitPreview() {
		CommitPreview preview = commitPreview;
		commitPreview = null;
		if (preview != null) {
			preview.thumbnail().close();
		}
	}

	/** The preview of {@code build}'s newest version, or null if it has not been asked for. */
	public static @Nullable Thumbnail thumbnail(BuildSummary build) {
		return thumbnail(build, build.latest());
	}

	/** The preview of {@code version} of {@code build}, or null if it has not been asked for. */
	public static @Nullable Thumbnail thumbnail(BuildSummary build, BuildSummary.Version version) {
		return THUMBNAILS.get(key(build, version));
	}

	/** Whether {@code build}'s newest version is small enough for its preview to be downloaded without being clicked. */
	public static boolean downloadsByItself(BuildSummary build) {
		return downloadsByItself(build.latest());
	}

	/** Whether {@code version} is small enough for its preview to be downloaded without being clicked. */
	public static boolean downloadsByItself(BuildSummary.Version version) {
		return version.extent().volume() <= ClientConfig.settings().autoDownloadLimit();
	}

	/** Asks the server for the preview of {@code build}'s newest version, unless it has been asked for already. */
	public static void download(BuildSummary build) {
		download(build, build.latest());
	}

	/** Asks the server for the preview of {@code version} of {@code build}, unless it has been asked for already. */
	public static void download(BuildSummary build, BuildSummary.Version version) {
		Thumbnail.Key key = key(build, version);
		Thumbnail existing = THUMBNAILS.get(key);
		if (existing != null && existing.state() != Thumbnail.State.FAILED) {
			return;
		}
		THUMBNAILS.put(key, new Thumbnail(key));
		ClientPlayNetworking.send(new ThumbnailRequestPayload(build.name(), version.version()));
	}

	private static Thumbnail.Key key(BuildSummary build, BuildSummary.Version version) {
		return new Thumbnail.Key(build.name(), version.version(), version.extent());
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
		if (version == CommitPreviewPayload.VERSION) {
			CommitPreview preview = commitPreview;
			Thumbnail thumbnail = preview == null ? null : preview.thumbnail();
			return thumbnail != null && thumbnail.key().build().equals(build)
				&& (thumbnail.state() == Thumbnail.State.REQUESTED || thumbnail.state() == Thumbnail.State.DOWNLOADING) ? thumbnail : null;
		}
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
		if (blocks == null) {
			return;
		}
		CommitPreview preview = commitPreview;
		BuildBox grid = thumbnail.grid();
		byte[] marks = preview != null && preview.thumbnail() == thumbnail && preview.marked() && grid != null ? preview.marks(grid) : null;
		mesh(thumbnail, blocks, marks);
	}

	/** Whether {@code thumbnail} is still wanted: not dropped or replaced since it was asked for. */
	private static boolean wanted(Thumbnail thumbnail) {
		CommitPreview preview = commitPreview;
		return THUMBNAILS.get(thumbnail.key()) == thumbnail || preview != null && preview.thumbnail() == thumbnail;
	}

	/**
	 * Builds the mesh of {@code thumbnail} from its {@code blocks}, with the changed ones {@code marks} marks, see
	 * {@link ThumbnailMesher}, on {@link #MESHER} and uploads it on the client thread.
	 */
	private static void mesh(Thumbnail thumbnail, BlockState[] blocks, byte @Nullable [] marks) {
		Minecraft client = Minecraft.getInstance();
		ThumbnailMesher.Context context = ThumbnailMesher.Context.of(client);
		int started = generation;
		BuildBox grid = thumbnail.grid();
		int scale = thumbnail.scale();
		if (grid == null) {
			thumbnail.fail(Thumbnail.FAILED, true);
			return;
		}

		MESHER.execute(() -> {
			ThumbnailMesher.Result result;
			try {
				result = ThumbnailMesher.mesh(grid, blocks, marks, context);
			} catch (ThumbnailMesher.TooDetailedException e) {
				MCVCS.LOGGER.warn("Not previewing '{}' v{}: more than {} vertices", thumbnail.key().build(), thumbnail.key().version(), ThumbnailMesher.MAX_VERTICES);
				client.execute(() -> thumbnail.fail(e.getMessage(), false));
				return;
			} catch (RuntimeException e) {
				MCVCS.LOGGER.error("Failed to build the preview of '{}' v{}", thumbnail.key().build(), thumbnail.key().version(), e);
				client.execute(() -> thumbnail.fail(Thumbnail.FAILED, true));
				return;
			}
			client.execute(() -> {
				if (generation != started || !wanted(thumbnail)) {
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
		dropCommitPreview();
		builds = List.of();
		listState = ListState.NONE;
		refreshOnSync = false;
		status = null;
	}
}
