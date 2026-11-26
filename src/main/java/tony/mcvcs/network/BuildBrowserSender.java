package tony.mcvcs.network;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.diff.BlockChange;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.diff.ChangeKind;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.PreviewGrid;
import tony.mcvcs.build.Schematic;
import tony.mcvcs.command.VcsCommand;

/**
 * Server side of the builds overlay: answers a client that asks for the builds in the world with a
 * {@link BuildListPayload}, and one that asks for the blocks of a version with a {@link ThumbnailBeginPayload} and
 * {@link ThumbnailBlocksPayload}s, or a {@link ThumbnailFailedPayload}. The commit window's request is answered the
 * same way for the blocks of the selected placement, preceded by what changed, see {@link CommitPreviewPayload}.
 * <p>
 * Both answer only players who may run {@code /vcs}, since the blocks of every build are as much the mod's data as
 * the commands are. A big version is sent as a coarser grid of itself, see {@link PreviewGrid}. Reading a schematic and cutting it into slices can take a while for a big build, so it is done on
 * a thread of its own, one request after another: a version's schematic is never rewritten once saved, so nothing on
 * the server thread can change it underneath. Payloads may be sent from any thread.
 */
public final class BuildBrowserSender {
	/** Reads schematics for previews one at a time, in the order they were asked for, away from the server thread. */
	private static final ExecutorService READER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MCVCS preview reader");
		thread.setDaemon(true);
		return thread;
	});

	private BuildBrowserSender() {
	}

	/** Registers the payload types and the receivers; must run on both sides, so it belongs in the main entrypoint. */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(BuildListRequestPayload.TYPE, BuildListRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ThumbnailRequestPayload.TYPE, ThumbnailRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BuildListPayload.TYPE, BuildListPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ThumbnailBeginPayload.TYPE, ThumbnailBeginPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ThumbnailBlocksPayload.TYPE, ThumbnailBlocksPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ThumbnailFailedPayload.TYPE, ThumbnailFailedPayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(BuildListRequestPayload.TYPE, (payload, context) -> sendList(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(ThumbnailRequestPayload.TYPE, (payload, context) -> sendThumbnail(context.player(), payload.build(), payload.version()));
		PayloadTypeRegistry.serverboundPlay().register(PlacementStatusRequestPayload.TYPE, PlacementStatusRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlacementStatusPayload.TYPE, PlacementStatusPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PlacementStatusRequestPayload.TYPE, (payload, context) -> sendStatus(context.player()));
		PayloadTypeRegistry.serverboundPlay().register(CommitPreviewRequestPayload.TYPE, CommitPreviewRequestPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CommitPreviewPayload.TYPE, CommitPreviewPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CommitChangesPayload.TYPE, CommitChangesPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(CommitPreviewRequestPayload.TYPE, (payload, context) -> sendCommitPreview(context.player()));
	}

	/**
	 * Tells {@code player} what committing their selected placement would save: how many blocks were added, removed and
	 * changed since the version it holds, which blocks those are, and every block in its box, sampled the way a
	 * version's preview is. The box is copied on the server thread, since it reads the world; the rest is worked out on
	 * {@link #READER}.
	 */
	private static void sendCommitPreview(ServerPlayer player) {
		Optional<BuildPlacement> selected = allowed(player) ? BuildRegistry.selected(player) : Optional.empty();
		ServerLevel level = selected.map(placement -> player.level().getServer().getLevel(placement.dimension())).orElse(null);
		if (selected.isEmpty() || level == null) {
			ServerPlayNetworking.send(player, new CommitPreviewPayload("", "", CommitPreviewPayload.UNKNOWN, CommitPreviewPayload.UNKNOWN, CommitPreviewPayload.UNKNOWN));
			return;
		}
		BuildPlacement placement = selected.get();
		String name = placement.build().name();
		int version = CommitPreviewPayload.VERSION;
		BuildBox box = placement.box();
		BoxSnapshot now = BoxSnapshot.ofLevel(box, level);
		READER.execute(() -> {
			try {
				int scale = PreviewGrid.scaleFor(box);
				BuildBox grid = PreviewGrid.grid(box, scale);
				// One byte per block of the grid: 0 for unchanged, otherwise one more than the kind of change.
				byte[] marks = new byte[Math.toIntExact(grid.volume())];
				int[] counts = new int[ChangeKind.values().length];
				boolean known = true;
				try {
					BuildDiff diff = BuildDiff.between(
						BoxSnapshot.ofSchematic(box, BuildStorage.readSchematic(name, placement.head()), box), now);
					for (BlockChange change : diff.changes()) {
						ChangeKind kind = change.kind();
						counts[kind.ordinal()]++;
						BlockPos pos = change.pos().subtract(box.min());
						int cell = grid.index(pos.getX() / scale, pos.getY() / scale, pos.getZ() / scale);
						byte mark = (byte) (kind.ordinal() + 1);
						marks[cell] = marks[cell] == 0 || marks[cell] == mark ? mark : (byte) (ChangeKind.CHANGED.ordinal() + 1);
					}
				} catch (IOException | IllegalArgumentException e) {
					// As for the commit itself, a version that cannot be compared does not stop the new one being shown.
					MCVCS.LOGGER.warn("Failed to compare '{}' with v{} for {}", placement.label(), placement.head(), player.getGameProfile().name(), e);
					known = false;
				}
				ServerPlayNetworking.send(player, known
					? new CommitPreviewPayload(name, placement.label(), counts[ChangeKind.ADDED.ordinal()], counts[ChangeKind.REMOVED.ordinal()], counts[ChangeKind.CHANGED.ordinal()])
					: new CommitPreviewPayload(name, placement.label(), CommitPreviewPayload.UNKNOWN, CommitPreviewPayload.UNKNOWN, CommitPreviewPayload.UNKNOWN));
				sendMarks(player, name, marks);

				BoxSnapshot sampled = PreviewGrid.sample(now, box, scale);
				ServerPlayNetworking.send(player, new ThumbnailBeginPayload(name, version, box, scale));
				PreviewSender.slices(sampled, (offset, palette, indices, last) ->
					ServerPlayNetworking.send(player, new ThumbnailBlocksPayload(name, version, offset, palette, indices, last)));
			} catch (RuntimeException e) {
				MCVCS.LOGGER.error("Failed to send the commit preview of '{}' to {}", placement.label(), player.getGameProfile().name(), e);
				ServerPlayNetworking.send(player, new ThumbnailFailedPayload(name, version, "Failed to read the placement"));
			}
		});
	}

	/** Sends every marked block of {@code marks} as {@link CommitChangesPayload}s. */
	private static void sendMarks(ServerPlayer player, String name, byte[] marks) {
		IntArrayList cells = new IntArrayList();
		ByteArrayList kinds = new ByteArrayList();
		for (int cell = 0; cell < marks.length; cell++) {
			if (marks[cell] != 0) {
				cells.add(cell);
				kinds.add((byte) (marks[cell] - 1));
			}
			if (cells.size() == CommitChangesPayload.PER_PACKET || (cell == marks.length - 1 && !cells.isEmpty())) {
				ServerPlayNetworking.send(player, new CommitChangesPayload(name, cells.toIntArray(), kinds.toByteArray()));
				cells.clear();
				kinds.clear();
			}
		}
	}

	/**
	 * Tells {@code player} how many blocks of their selected placement differ from the version it holds, the same count
	 * {@code /vcs checkout} refuses over. Worked out on the server thread, since it reads the world.
	 */
	private static void sendStatus(ServerPlayer player) {
		Optional<BuildPlacement> selected = allowed(player) ? BuildRegistry.selected(player) : Optional.empty();
		if (selected.isEmpty()) {
			ServerPlayNetworking.send(player, new PlacementStatusPayload("", PlacementStatusPayload.UNKNOWN));
			return;
		}
		BuildPlacement placement = selected.get();
		ServerLevel level = player.level().getServer().getLevel(placement.dimension());
		int modified = PlacementStatusPayload.UNKNOWN;
		if (level != null) {
			try {
				BuildBox box = placement.box();
				modified = BuildDiff.between(
					BoxSnapshot.ofSchematic(box, BuildStorage.readSchematic(placement.build().name(), placement.head()), box),
					BoxSnapshot.ofLevel(box, level)).size();
			} catch (IOException | RuntimeException e) {
				MCVCS.LOGGER.error("Failed to count the modified blocks of '{}' for {}", placement.label(), player.getGameProfile().name(), e);
			}
		}
		ServerPlayNetworking.send(player, new PlacementStatusPayload(placement.label(), modified));
	}

	/** Whether {@code player} may browse builds: the same permission {@code /vcs} needs. */
	public static boolean allowed(ServerPlayer player) {
		return VcsCommand.PERMISSION.check(player.createCommandSourceStack().permissions());
	}

	/** Tells {@code player} every build in their world, or only that they may not see them. */
	private static void sendList(ServerPlayer player) {
		if (!allowed(player)) {
			ServerPlayNetworking.send(player, new BuildListPayload(false, List.of()));
			return;
		}
		MinecraftServer server = player.level().getServer();
		List<BuildSummary> builds = BuildRegistry.all(server).stream().map(BuildSummary::of).toList();
		ServerPlayNetworking.send(player, new BuildListPayload(true, builds));
	}

	/**
	 * Sends {@code player} the blocks of version {@code version} of the build called {@code name}, laid out over the
	 * version's own extent. The build is looked up here, on the server thread; the schematic is read on
	 * {@link #READER}.
	 */
	private static void sendThumbnail(ServerPlayer player, String name, int version) {
		if (!allowed(player)) {
			ServerPlayNetworking.send(player, new ThumbnailFailedPayload(name, version, "No permission"));
			return;
		}
		Optional<Build> found = Build.isValidName(name) ? BuildRegistry.find(player.level().getServer(), name) : Optional.empty();
		if (found.isEmpty()) {
			ServerPlayNetworking.send(player, new ThumbnailFailedPayload(name, version, "No such build"));
			return;
		}
		Build build = found.get();
		if (!build.hasVersion(version)) {
			ServerPlayNetworking.send(player, new ThumbnailFailedPayload(name, version, "No v" + version));
			return;
		}

		BuildBox extent = build.extent(version);
		int scale = PreviewGrid.scaleFor(extent);
		READER.execute(() -> {
			try {
				Schematic schematic = BuildStorage.readSchematic(name, version);
				BoxSnapshot snapshot = PreviewGrid.sample(schematic, extent, scale);
				ServerPlayNetworking.send(player, new ThumbnailBeginPayload(name, version, extent, scale));
				PreviewSender.slices(snapshot, (offset, palette, indices, last) ->
					ServerPlayNetworking.send(player, new ThumbnailBlocksPayload(name, version, offset, palette, indices, last)));
			} catch (NoSuchFileException e) {
				ServerPlayNetworking.send(player, new ThumbnailFailedPayload(name, version, "Schematic missing"));
			} catch (IOException | RuntimeException e) {
				MCVCS.LOGGER.error("Failed to send the preview of build '{}' v{} to {}", name, version, player.getGameProfile().name(), e);
				ServerPlayNetworking.send(player, new ThumbnailFailedPayload(name, version, "Failed to read schematic"));
			}
		});
	}
}
