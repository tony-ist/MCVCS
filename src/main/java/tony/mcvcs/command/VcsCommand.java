package tony.mcvcs.command;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;

import tony.mcvcs.MCVCS;
import tony.mcvcs.project.Project;
import tony.mcvcs.project.ProjectRegistry;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;

/**
 * {@code /vcs} command tree.
 * <ul>
 * <li>{@code /vcs create <buildname>}: copies the player's current WorldEdit selection and saves it as a schematic
 * in WorldEdit's schematics directory, the same place {@code //schem save} writes to. The new project becomes the
 * player's selected project for this world.</li>
 * <li>{@code /vcs commit}: saves the selected project's region again as {@code <buildname>_v<N>}. The region is the
 * one captured by {@code /vcs create}; the player's current WorldEdit selection is ignored.</li>
 * </ul>
 */
public final class VcsCommand {
	/** Corner of the selection the schematic origin is anchored to. */
	public static final Corner ORIGIN_CORNER = Corner.TOP_NORTH_WEST;
	/**
	 * Extra offset applied on top of {@link #ORIGIN_CORNER}.
	 * <p>
	 * Pasting puts the origin at the player's feet, so anchoring straight to the top layer leaves the player standing
	 * inside it. Lifting the origin one block above the selection drops the whole build one block below the player.
	 */
	public static final BlockVector3 ORIGIN_OFFSET = BlockVector3.at(0, 1, 0);
	/** Schematic file format. */
	public static final ClipboardFormat FORMAT = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC;
	public static final boolean COPY_ENTITIES = false;
	public static final boolean COPY_BIOMES = false;
	/** Vanilla permission required to run the command (gamemasters = op level 2 / cheats). */
	public static final PermissionCheck PERMISSION = Commands.LEVEL_GAMEMASTERS;

	/** A corner of a cuboid; north is -Z, west is -X, bottom is -Y. */
	public enum Corner {
		BOTTOM_NORTH_WEST(false, false, false),
		BOTTOM_NORTH_EAST(true, false, false),
		BOTTOM_SOUTH_WEST(false, false, true),
		BOTTOM_SOUTH_EAST(true, false, true),
		TOP_NORTH_WEST(false, true, false),
		TOP_NORTH_EAST(true, true, false),
		TOP_SOUTH_WEST(false, true, true),
		TOP_SOUTH_EAST(true, true, true);

		private final boolean east;
		private final boolean top;
		private final boolean south;

		Corner(boolean east, boolean top, boolean south) {
			this.east = east;
			this.top = top;
			this.south = south;
		}

		public BlockVector3 of(Region region) {
			BlockVector3 min = region.getMinimumPoint();
			BlockVector3 max = region.getMaximumPoint();
			return BlockVector3.at(
				east ? max.x() : min.x(),
				top ? max.y() : min.y(),
				south ? max.z() : min.z()
			);
		}
	}

	private VcsCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("vcs")
				.requires(Commands.hasPermission(PERMISSION))
				.then(Commands.literal("create")
					.then(Commands.argument("buildname", StringArgumentType.word())
						.executes(context -> create(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(Commands.literal("commit")
					.executes(context -> commit(context.getSource())))));
	}

	private static int create(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		WorldEdit worldEdit = WorldEdit.getInstance();
		LocalSession session = worldEdit.getSessionManager().get(actor);

		try {
			// Clone so later //pos1, //pos2 or wand clicks cannot move the project's region under us.
			Project project = new Project(buildName, session.getSelection(actor.getWorld()).clone(), 1);
			Path file = save(actor, session, project);
			ProjectRegistry.select(player, project);

			source.sendSuccess(() -> Component.literal("Created build '" + buildName + "' (" + project.region().getVolume() + " blocks) at " + file.getFileName()), false);
			return 1;
		} catch (IncompleteRegionException e) {
			source.sendFailure(Component.literal("Make a WorldEdit selection first"));
			return 0;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to create build '{}' from selection of {}", buildName, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}

	private static int commit(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Project> selected = ProjectRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("No build selected in this world; run /vcs create <buildname> first"));
			return 0;
		}

		Project project = selected.get().nextVersion();
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Path file = save(actor, session, project);
			ProjectRegistry.select(player, project);

			source.sendSuccess(() -> Component.literal("Committed build '" + project.name() + "' v" + project.version() + " (" + project.region().getVolume() + " blocks) at " + file.getFileName()), false);
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to commit build '{}' v{} for {}", project.name(), project.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}

	/** Copies the project's region into a clipboard anchored at the configured origin and writes it to disk. */
	private static Path save(Player actor, LocalSession session, Project project) throws WorldEditException, IOException {
		Region region = project.region();
		WorldEdit worldEdit = WorldEdit.getInstance();
		World world = region.getWorld();

		BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
		clipboard.setOrigin(ORIGIN_CORNER.of(region).add(ORIGIN_OFFSET));

		try (EditSession editSession = session.createEditSession(actor)) {
			ForwardExtentCopy copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
			copy.setCopyingEntities(COPY_ENTITIES);
			copy.setCopyingBiomes(COPY_BIOMES);
			Operations.complete(copy);
		}

		Path dir = worldEdit.getWorkingDirectoryPath(worldEdit.getConfiguration().saveDir);
		// Validates the user-supplied name and prevents escaping the schematics directory.
		Path file = worldEdit.getSafeSaveFile(actor, dir.toFile(), project.fileName(), FORMAT.getPrimaryFileExtension()).toPath();
		Files.createDirectories(file.getParent());

		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
			 ClipboardWriter writer = FORMAT.getWriter(out)) {
			writer.write(clipboard);
		}

		MCVCS.LOGGER.info("{} saved build '{}' v{} from {} at {}", actor.getName(), project.name(), project.version(), world == null ? "its region" : "its region in " + world.getName(), file);
		return file;
	}
}
