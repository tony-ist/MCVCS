package tony.mcvcs.network;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.command.VcsCommand;

/**
 * Server side of the builds overlay: answers a client that asks for the builds in the world with a
 * {@link BuildListPayload}, and one that asks for the blocks of a version with a {@link ThumbnailBeginPayload} and
 * {@link ThumbnailBlocksPayload}s, or a {@link ThumbnailFailedPayload}.
 * <p>
 * Both answer only players who may run {@code /vcs}, since the blocks of every build are as much the mod's data as
 * the commands are. Reading a schematic and cutting it into slices can take a while for a big build, so it is done on
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
		READER.execute(() -> {
			try {
				Clipboard clipboard = BuildStorage.readSchematic(name, version);
				BoxSnapshot snapshot = BoxSnapshot.ofClipboard(extent, clipboard, extent);
				ServerPlayNetworking.send(player, new ThumbnailBeginPayload(name, version, extent));
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
