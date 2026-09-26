package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import tony.mcvcs.network.ChatButtons;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.session.ClipboardHolder;

/**
 * {@code /vcs load [version | tag]}: puts that version's schematic of the selected placement's build, or the latest one if
 * no version is given, into the player's WorldEdit clipboard, as {@code //copy} or {@code //schem load} would, so
 * {@code //paste} places it. The schematic stays where it is; nothing is written to WorldEdit's own schematic folder.
 * <p>
 * The clipboard is anchored at the corner {@link BuildSaver#ORIGIN_CORNER} names, so {@code //paste} drops the build
 * just below the player wherever they stand, the same spot {@code /vcs place} would put it. What that pastes is
 * loose blocks, though: only {@code /vcs place} makes a placement the mod goes on tracking.
 */
public final class VcsCommandLoad {
	static final VcsHelp HELP = new VcsHelp("load", "/vcs load [version | tag]",
		"put a version in your WorldEdit clipboard for //paste",
		"Puts that version of the selected placement's build, given by number or by tag, or the latest one if none is given, into your WorldEdit clipboard, replacing whatever you had copied, so //paste places it. The origin is one block above the top north-west corner, so //paste puts the build one block below your feet, extending east and south. What you paste is only blocks; run /vcs place to add a placement the mod keeps track of.");

	private VcsCommandLoad() {
	}

	/** @param version the version to load, by number or tag, or {@link VersionRef#DEFAULT} for the build's latest one */
	static int run(CommandSourceStack source, VersionRef version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		Build build = selected.get().build();
		Optional<Integer> resolved = version.resolve(source, build, build.version());
		if (resolved.isEmpty()) {
			return 0;
		}
		int loaded = resolved.get();
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Clipboard clipboard = BuildStorage.readSchematic(build.name(), loaded);
			// Same as //schem load: the clipboard replaces whatever the player had copied, origin and all, so //paste
			// puts the build at their feet the way BuildSaver.ORIGIN_CORNER and ORIGIN_OFFSET arranged it.
			session.setClipboard(new ClipboardHolder(clipboard));

			source.sendSuccess(() -> Component.literal("Loaded build ").append(VcsMessages.name(build.name())).append(" " + build.versionLabel(loaded)
				+ " (" + build.extent(loaded).volume() + " blocks) into your clipboard; run ").append(ChatButtons.command("//paste")).append(" to place it"), false);
			return 1;
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build ").append(VcsMessages.name(build.name())).append(" v" + loaded + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to load build '{}' v{} for {}", build.name(), loaded, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}
	}
}
