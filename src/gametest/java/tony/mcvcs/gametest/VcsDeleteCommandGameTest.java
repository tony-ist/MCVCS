package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.buildFile;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.lookAt;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.read;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.diff.ClientDiff;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.preview.ClientPreview;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.build.ClientPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /vcs delete <buildname>} only asks for confirmation and leaves the build alone; {@code /vcs confirmDelete}
 * then removes the build's folder with every version in it, drops the player's selection of it along with any
 * preview or diff highlighting, and tells the client the build is gone. Confirming with nothing asked refuses.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsDeleteCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-delete";
	private static final String CONFIRMATION = "Delete build " + BUILD_NAME + ", leaving its placements standing as blocks? This cannot be undone, all versions will be lost! Run /vcs confirmDelete to proceed.";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		// Before the world exists: the player is told their selection on join, so it must be gone by then.
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			// A 3x2x2 stone box in front of the player, saved as two versions.
			BlockPos min = playerPos(singleplayer).offset(2, 0, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.STONE.defaultBlockState());
			select(singleplayer, min, max);
			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			read(schematic(BUILD_NAME, 1));
			runCommand(context, "vcs commit");
			read(schematic(BUILD_NAME, 2));
			waitForSelection(context, BUILD_NAME, 2);

			// Nothing has been asked yet, so there is nothing to confirm.
			List<Component> unasked = run(context, "vcs confirmDelete");
			assertOnlyMessage(unasked, "Nothing to confirm");
			assertBuildOnDisk();

			// Asking for a build that does not exist refuses and asks nothing.
			List<Component> missing = run(context, "vcs delete " + BUILD_NAME + "-missing");
			assertOnlyMessage(missing, "No build named " + BUILD_NAME + "-missing");
			List<Component> stillUnasked = run(context, "vcs confirmDelete");
			assertOnlyMessage(stillUnasked, "Nothing to confirm");
			assertBuildOnDisk();

			// Asking only prints the confirmation; the build, the selection and the versions stay.
			List<Component> asked = run(context, "vcs delete " + BUILD_NAME);
			assertOnlyMessage(asked, CONFIRMATION);
			assertBuildOnDisk();
			assertSelected(context, singleplayer, BUILD_NAME, 2);

			// Preview v1 and highlight the diff against it, so both are up when the build goes.
			setBlock(singleplayer, min, Blocks.DIAMOND_BLOCK.defaultBlockState());
			runCommand(context, "vcs preview 1");
			context.waitFor(client -> PreviewManager.active() != null);
			runCommand(context, "vcs diff 1");
			context.waitFor(client -> DiffManager.active() != null);

			// Confirming removes everything and the client hears that the build is gone.
			List<Component> deleted = run(context, "vcs confirmDelete");
			assertOnlyMessage(deleted, "Deleted build " + BUILD_NAME + " and its 2 versions");
			context.waitFor(client -> ClientPlacements.selected() == null && ClientPlacements.all().isEmpty());
			assertBuildGone();
			assertNothingSelected(context, singleplayer);
			assertNoPreviewOrDiff(context);

			lookAt(context, min, max);
			screenshotLastFrame(context, "mcvcs-vcs-delete");

			// The confirmation was used up, so a second one has nothing to act on.
			List<Component> reconfirmed = run(context, "vcs confirmDelete");
			assertOnlyMessage(reconfirmed, "Nothing to confirm");

			// The name is free again and the old versions do not come back with it.
			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			read(schematic(BUILD_NAME, 1));
			waitForSelection(context, BUILD_NAME, 1);
			if (Files.exists(schematic(BUILD_NAME, 2))) {
				throw new AssertionError("Recreating the build must not bring back " + schematic(BUILD_NAME, 2));
			}
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	private static void assertOnlyMessage(List<Component> messages, String prefix) {
		if (messages.size() != 1 || !messages.get(0).getString().startsWith(prefix)) {
			throw new AssertionError("Expected only a message starting with '" + prefix + "' but got " + messages.stream().map(Component::getString).toList());
		}
	}

	private static void assertBuildOnDisk() {
		read(schematic(BUILD_NAME, 1));
		read(schematic(BUILD_NAME, 2));
		if (!Files.isRegularFile(buildFile(BUILD_NAME))) {
			throw new AssertionError("Expected " + buildFile(BUILD_NAME) + " to still exist");
		}
	}

	private static void assertBuildGone() {
		if (Files.exists(BuildStorage.directory(BUILD_NAME))) {
			throw new AssertionError("Expected " + BuildStorage.directory(BUILD_NAME) + " to be deleted");
		}
	}

	private static void assertSelected(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name, int version) {
		Optional<BuildPlacement> selected = singleplayer.getServer().computeOnServer(server -> BuildRegistry.selected(server.getPlayerList().getPlayers().get(0)));
		if (selected.isEmpty() || !selected.get().build().name().equals(name) || selected.get().head() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " on the server but got " + selected.orElse(null));
		}
		ClientPlacement shown = context.computeOnClient(client -> ClientPlacements.selected());
		if (shown == null || !shown.build().equals(name) || shown.head() != version) {
			throw new AssertionError("Expected selection '" + name + "' v" + version + " on the client but got " + shown);
		}
	}

	private static void assertNothingSelected(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		Optional<BuildPlacement> selected = singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			return BuildRegistry.selected(player);
		});
		if (selected.isPresent()) {
			throw new AssertionError("Expected no selection on the server but got " + selected.get());
		}
		ClientPlacement shown = context.computeOnClient(client -> ClientPlacements.selected());
		if (shown != null) {
			throw new AssertionError("Expected no selection on the client but got '" + shown.label() + "' v" + shown.head());
		}
	}

	private static void assertNoPreviewOrDiff(ClientGameTestContext context) {
		context.waitTicks(5);
		ClientPreview preview = context.computeOnClient(client -> PreviewManager.active());
		if (preview != null) {
			throw new AssertionError("Expected no preview after /vcs confirmDelete but '" + preview.name() + "' v" + preview.version() + " is shown");
		}
		ClientDiff diff = context.computeOnClient(client -> DiffManager.active());
		if (diff != null) {
			throw new AssertionError("Expected no diff after /vcs confirmDelete but '" + diff.name() + "' v" + diff.version() + " is highlighted");
		}
	}

	private static void waitForSelection(ClientGameTestContext context, String name, int version) {
		context.waitFor(client -> {
			ClientPlacement selected = ClientPlacements.selected();
			return selected != null && selected.build().equals(name) && selected.head() == version;
		});
	}
}
