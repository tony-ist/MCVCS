package tony.mcvcs.command;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;

import tony.mcvcs.MCVCS;
import tony.mcvcs.diff.BuildDiff;
import tony.mcvcs.diff.ChangeKind;
import tony.mcvcs.network.ChatButtons;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;
import tony.mcvcs.network.BuildSync;
import tony.mcvcs.build.BoxSnapshot;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;

/**
 * {@code /vcs} command tree.
 * <ul>
 * <li>{@code /vcs create <buildname>}: copies the bounding box of the player's current WorldEdit selection and saves
 * it as version 1 of the build, in the build's own folder under {@code mcvcs/} in the game directory, see
 * {@link BuildStorage}. The box may not overlap any existing build in the same dimension. The new build becomes
 * the player's selected build.</li>
 * <li>{@code /vcs select <buildname>}: makes an existing build the player's selected build, so its bounding
 * box is shown and later commands act on it.</li>
 * <li>{@code /vcs builds}: lists every build in the world, each with a chat button that runs
 * {@code /vcs select} for it, see {@link ChatButtons}; the selected one is marked instead.</li>
 * <li>{@code /vcs deselect}: leaves the player with no selected build, so no bounding box is shown and commands
 * that need a selection refuse until one is made again. Any preview or diff highlighting of the build is turned
 * off with it.</li>
 * <li>{@code /vcs commit}: saves the selected build's box again as its next version. The box is the one
 * captured by {@code /vcs create}; the player's current WorldEdit selection is ignored.</li>
 * <li>{@code /vcs preview <version>}: sends that version's schematic to the player's client, which draws it in place
 * of the real blocks inside the build's box. Nothing in the world changes. {@code /vcs preview off} shows the
 * real blocks again.</li>
 * <li>{@code /vcs load [version]}: puts that version's schematic, or the latest one if no version is given, into
 * the player's WorldEdit clipboard, as {@code //copy} or {@code //schem load} would, so {@code //paste} places it.
 * The schematic stays where it is; nothing is written to WorldEdit's own schematic folder.</li>
 * <li>{@code /vcs diff [version]}: compares the blocks currently inside the build's box with that version, or the
 * latest one if no version is given, see {@link BuildDiff}, reports how many were added, removed or changed since,
 * and has the player's client highlight them in place. {@code /vcs diff off} stops the highlighting.</li>
 * <li>{@code /vcs delete <buildname>}: asks the player to confirm deleting the build; nothing is touched yet.
 * {@code /vcs confirmDelete} then removes the build's folder with every version in it and every player's
 * selection of it. The confirmation is remembered until it is used, replaced by another {@code /vcs delete}, or
 * the player leaves.</li>
 * </ul>
 * Builds and selections are looked up through {@link BuildRegistry}, which only shows those belonging to the
 * world being played; a build remembers which world and dimension its box is in.
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
	public static final boolean COPY_ENTITIES = false;
	public static final boolean COPY_BIOMES = false;
	/** Vanilla permission required to run the command (gamemasters = op level 2 / cheats). */
	public static final PermissionCheck PERMISSION = Commands.LEVEL_GAMEMASTERS;
	/** Label of the button {@code /vcs builds} puts after each build that is not selected. */
	public static final String SELECT_BUTTON = "Select";
	/** Marker {@code /vcs builds} puts after the selected build instead of a button. */
	public static final String SELECTED_MARKER = "selected";
	/** Version number standing for the selected build's latest version, used when {@code /vcs load} or {@code /vcs diff} is given none. */
	private static final int LATEST = 0;
	/**
	 * The build each player's last {@code /vcs delete} asked to delete and {@code /vcs confirmDelete} will act on,
	 * by player UUID. Only the server thread touches this; a player's entry goes when they leave.
	 */
	private static final Map<UUID, Build> PENDING_DELETES = new HashMap<>();

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
		// A confirmation left behind by a player who logged out must not delete anything when they are back.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING_DELETES.remove(handler.player.getUUID()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("vcs")
				.requires(Commands.hasPermission(PERMISSION))
				.then(Commands.literal("create")
					.then(Commands.argument("buildname", StringArgumentType.word())
						.executes(context -> create(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(Commands.literal("select")
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> select(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(Commands.literal("builds")
					.executes(context -> builds(context.getSource())))
				.then(Commands.literal("deselect")
					.executes(context -> deselect(context.getSource())))
				.then(Commands.literal("commit")
					.executes(context -> commit(context.getSource())))
				.then(Commands.literal("preview")
					.then(Commands.literal("off")
						.executes(context -> previewOff(context.getSource())))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> preview(context.getSource(), IntegerArgumentType.getInteger(context, "version")))))
				.then(Commands.literal("load")
					.executes(context -> load(context.getSource(), LATEST))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> load(context.getSource(), IntegerArgumentType.getInteger(context, "version")))))
				.then(Commands.literal("diff")
					.executes(context -> diff(context.getSource(), LATEST))
					.then(Commands.literal("off")
						.executes(context -> diffOff(context.getSource())))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> diff(context.getSource(), IntegerArgumentType.getInteger(context, "version")))))
				.then(Commands.literal("delete")
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> delete(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(Commands.literal("confirmDelete")
					.executes(context -> confirmDelete(context.getSource())))));
	}

	/** Every version number of the build the source player has selected; nothing if there is no player or selection. */
	private static List<String> versions(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return List.of();
		}
		return BuildRegistry.selected(player)
			.map(build -> IntStream.rangeClosed(1, build.version()).mapToObj(Integer::toString).toList())
			.orElse(List.of());
	}

	private static int create(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		// The name becomes a folder on disk, so it has to be checked before anything is written under it.
		if (!Build.isValidName(buildName)) {
			source.sendFailure(Component.literal("Build name '" + buildName + "' may only contain letters, digits, _ + - and dots between them"));
			return 0;
		}
		// Build folders are shared by every world in the game directory, so a name can only belong to one world.
		String world = Build.worldOf(source.getServer());
		Optional<Build> taken = BuildRegistry.findInAnyWorld(buildName).filter(build -> !build.world().equals(world));
		if (taken.isPresent()) {
			source.sendFailure(Component.literal("Build name '" + buildName + "' is already used by a build in world '" + taken.get().world() + "'"));
			return 0;
		}
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		WorldEdit worldEdit = WorldEdit.getInstance();
		LocalSession session = worldEdit.getSessionManager().get(actor);

		try {
			// Only the bounding box is kept, so later //pos1, //pos2 or wand clicks cannot move the build's box under us.
			Build build = new Build(buildName, world, player.level().dimension(), BuildBox.of(session.getSelection(actor.getWorld())), 1);
			// Every block belongs to at most one build, so a box that overlaps an existing build in this dimension is refused.
			Optional<Build> overlapping = BuildRegistry.all(source.getServer()).stream()
				.filter(other -> other.dimension().equals(build.dimension()) && other.box().intersects(build.box()))
				.findFirst();
			if (overlapping.isPresent()) {
				source.sendFailure(Component.literal("Selection overlaps build '" + overlapping.get().name() + "'; builds may not intersect"));
				return 0;
			}
			Path file = save(actor, session, build, player.level());
			BuildRegistry.select(player, build);

			source.sendSuccess(() -> Component.literal("Created build '" + buildName + "' (" + build.box().volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
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

	private static int select(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named '" + buildName + "' in this world; create it with /vcs create " + buildName));
			return 0;
		}

		try {
			BuildRegistry.select(player, build.get());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to select build '{}' for {}", buildName, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save selection: " + e.getMessage()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Selected build '" + buildName + "' v" + build.get().version() + " (" + build.get().box().volume() + " blocks)"), false);
		return 1;
	}

	private static int builds(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<Build> builds = BuildRegistry.all(source.getServer());
		if (builds.isEmpty()) {
			source.sendFailure(Component.literal("No builds in this world; create one with /vcs create <buildname>"));
			return 0;
		}

		String selected = BuildRegistry.selected(player).map(Build::name).orElse(null);
		source.sendSuccess(() -> Component.literal(builds.size() + (builds.size() == 1 ? " build" : " builds") + " in this world:"), false);
		for (Build build : builds) {
			source.sendSuccess(() -> buildLine(build, build.name().equals(selected)), false);
		}
		return builds.size();
	}

	/**
	 * One line of {@code /vcs builds}: the build's name, version and size, followed by a clickable
	 * {@code [Select]} that runs {@code /vcs select} for it, or a {@code [selected]} marker if it already is.
	 */
	private static MutableComponent buildLine(Build build, boolean selected) {
		MutableComponent line = Component.literal("- " + build.name() + " v" + build.version() + " (" + build.box().volume() + " blocks) ");
		if (selected) {
			return line.append(ComponentUtils.wrapInSquareBrackets(Component.literal(SELECTED_MARKER)).withStyle(ChatFormatting.GRAY));
		}
		return line.append(ComponentUtils.wrapInSquareBrackets(Component.literal(SELECT_BUTTON))
			.withStyle(style -> style.withColor(ChatFormatting.GREEN)
				.withClickEvent(ChatButtons.select(build.name()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to run /vcs select " + build.name())))));
	}

	private static int deselect(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("No build selected in this world"));
			return 0;
		}

		String buildName = selected.get().name();
		try {
			BuildRegistry.deselect(player);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to deselect build '{}' for {}", buildName, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save selection: " + e.getMessage()));
			return 0;
		}
		// A preview or diff shows a version of the build that is no longer selected, so it goes with the selection.
		if (PreviewSender.canSend(player)) {
			PreviewSender.clear(player);
		}
		if (DiffSender.canSend(player)) {
			DiffSender.clear(player);
		}
		source.sendSuccess(() -> Component.literal("Deselected build '" + buildName + "'"), false);
		return 1;
	}

	private static int commit(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("No build selected in this world; run /vcs create <buildname> first"));
			return 0;
		}

		Build build = selected.get().nextVersion();
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build '" + build.name() + "' is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Path file = save(actor, session, build, level);
			BuildRegistry.select(player, build);

			source.sendSuccess(() -> Component.literal("Committed build '" + build.name() + "' v" + build.version() + " (" + build.box().volume() + " blocks) at " + BuildStorage.root().relativize(file)), false);
			return 1;
		} catch (WorldEditException | IOException e) {
			MCVCS.LOGGER.error("Failed to commit build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save schematic: " + e.getMessage()));
			return 0;
		}
	}

	private static int preview(CommandSourceStack source, int version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("No build selected in this world; run /vcs create <buildname> first"));
			return 0;
		}
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot show previews"));
			return 0;
		}

		Build latest = selected.get();
		if (version > latest.version()) {
			source.sendFailure(Component.literal("Build '" + latest.name() + "' only has versions 1 to " + latest.version()));
			return 0;
		}

		Build build = latest.atVersion(version);

		try {
			Clipboard clipboard = BuildStorage.read(build);
			PreviewSender.send(player, build, clipboard);

			source.sendSuccess(() -> Component.literal("Previewing build '" + build.name() + "' v" + build.version() + " (" + build.box().volume() + " blocks); run /vcs preview off to stop"), false);
			return 1;
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build '" + build.name() + "' v" + build.version() + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to preview build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}
	}

	private static int previewOff(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!PreviewSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot show previews"));
			return 0;
		}

		PreviewSender.clear(player);
		source.sendSuccess(() -> Component.literal("Preview off"), false);
		return 1;
	}

	/** @param version the version to load, or {@link #LATEST} for the selected build's latest one */
	private static int load(CommandSourceStack source, int version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("No build selected in this world; run /vcs create <buildname> first"));
			return 0;
		}

		Build latest = selected.get();
		if (version > latest.version()) {
			source.sendFailure(Component.literal("Build '" + latest.name() + "' only has versions 1 to " + latest.version()));
			return 0;
		}

		Build build = version == LATEST ? latest : latest.atVersion(version);
		Player actor = FabricAdapter.get().fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

		try {
			Clipboard clipboard = BuildStorage.read(build);
			// Same as //schem load: the clipboard replaces whatever the player had copied, origin and all, so //paste
			// puts the build at their feet the way ORIGIN_CORNER and ORIGIN_OFFSET arranged it.
			session.setClipboard(new ClipboardHolder(clipboard));

			source.sendSuccess(() -> Component.literal("Loaded build '" + build.name() + "' v" + build.version() + " (" + build.box().volume() + " blocks) into your clipboard; run //paste to place it"), false);
			return 1;
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build '" + build.name() + "' v" + build.version() + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to load build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}
	}

	/**
	 * Compares the blocks now inside the selected build's box with one of its versions. The summary goes to chat
	 * whether or not the player's client has this mod; the highlighting needs it.
	 *
	 * @param version the version to compare against, or {@link #LATEST} for the selected build's latest one
	 */
	private static int diff(CommandSourceStack source, int version) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(Component.literal("No build selected in this world; run /vcs create <buildname> first"));
			return 0;
		}

		Build latest = selected.get();
		if (version > latest.version()) {
			source.sendFailure(Component.literal("Build '" + latest.name() + "' only has versions 1 to " + latest.version()));
			return 0;
		}
		Build build = version == LATEST ? latest : latest.atVersion(version);
		ServerLevel level = source.getServer().getLevel(build.dimension());
		if (level == null) {
			source.sendFailure(Component.literal("Build '" + build.name() + "' is in " + build.dimension().identifier() + ", which does not exist here"));
			return 0;
		}

		BuildDiff diff;
		try {
			// The version is the old side and the world the new one, so "added" reads as "built since that version".
			diff = BuildDiff.between(BoxSnapshot.ofClipboard(build.box(), BuildStorage.read(build)), BoxSnapshot.ofLevel(build.box(), level));
		} catch (NoSuchFileException e) {
			source.sendFailure(Component.literal("No schematic for build '" + build.name() + "' v" + build.version() + " at " + e.getFile()));
			return 0;
		} catch (IOException | IllegalArgumentException e) {
			MCVCS.LOGGER.error("Failed to diff build '{}' v{} for {}", build.name(), build.version(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to load schematic: " + e.getMessage()));
			return 0;
		}

		boolean highlight = DiffSender.canSend(player);
		if (diff.isEmpty()) {
			// Nothing to highlight, and a stale highlight of an earlier diff would be misleading next to this message.
			if (highlight) {
				DiffSender.clear(player);
			}
			source.sendSuccess(() -> Component.literal("Build '" + build.name() + "' matches v" + build.version() + "; nothing to highlight"), false);
			return 0;
		}

		if (highlight) {
			DiffSender.send(player, build, diff);
		}
		String tail = highlight ? "; run /vcs diff off to stop highlighting" : "; install MCVCS on your client to see them highlighted";
		source.sendSuccess(() -> Component.literal(diffSummary(build, diff) + tail), false);
		return diff.size();
	}

	/** E.g. {@code 5 blocks in build 'x' differ from v2 (2 added, 1 removed, 2 changed)}. */
	private static String diffSummary(Build build, BuildDiff diff) {
		Map<ChangeKind, Integer> counts = diff.counts();
		return diff.size() + (diff.size() == 1 ? " block" : " blocks") + " in build '" + build.name() + "' "
			+ (diff.size() == 1 ? "differs" : "differ") + " from v" + build.version()
			+ " (" + counts.get(ChangeKind.ADDED) + " added, " + counts.get(ChangeKind.REMOVED) + " removed, " + counts.get(ChangeKind.CHANGED) + " changed)";
	}

	private static int diffOff(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!DiffSender.canSend(player)) {
			source.sendFailure(Component.literal("Your client does not have MCVCS installed, so it cannot highlight diffs"));
			return 0;
		}

		DiffSender.clear(player);
		source.sendSuccess(() -> Component.literal("Diff off"), false);
		return 1;
	}

	/** Only asks for confirmation; {@link #confirmDelete} does the deleting. */
	private static int delete(CommandSourceStack source, String buildName) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> build = BuildRegistry.find(source.getServer(), buildName);
		if (build.isEmpty()) {
			source.sendFailure(Component.literal("No build named '" + buildName + "' in this world"));
			return 0;
		}

		PENDING_DELETES.put(player.getUUID(), build.get());
		source.sendSuccess(() -> Component.literal("Delete build " + buildName + "? This cannot be undone, all versions will be lost! Type `/vcs confirmDelete` to proceed."), false);
		return 1;
	}

	private static int confirmDelete(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		Build build = PENDING_DELETES.remove(player.getUUID());
		if (build == null) {
			source.sendFailure(Component.literal("Nothing to confirm; run /vcs delete <buildname> first"));
			return 0;
		}
		// The build may have gone, or the player moved to another world, since they asked.
		if (!build.isIn(server) || BuildRegistry.find(server, build.name()).isEmpty()) {
			source.sendFailure(Component.literal("No build named '" + build.name() + "' in this world"));
			return 0;
		}

		// Anyone previewing or diffing the build is looking at a version that is about to disappear.
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (BuildRegistry.selected(other).filter(selected -> selected.name().equals(build.name())).isEmpty()) {
				continue;
			}
			if (PreviewSender.canSend(other)) {
				PreviewSender.clear(other);
			}
			if (DiffSender.canSend(other)) {
				DiffSender.clear(other);
			}
		}

		try {
			BuildStorage.delete(build.name());
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to delete build '{}' for {}", build.name(), player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to delete build: " + e.getMessage()));
			// Whatever was removed before the failure is gone for everyone, so the clients must hear about it anyway.
			BuildSync.broadcast(server);
			return 0;
		}
		MCVCS.LOGGER.info("{} deleted build '{}' with {} versions", player.getGameProfile().name(), build.name(), build.version());
		// The build and any selection of it are gone, so every client's list and possibly its selection changed.
		BuildSync.broadcast(server);

		source.sendSuccess(() -> Component.literal("Deleted build '" + build.name() + "' and its " + build.version() + (build.version() == 1 ? " version" : " versions")), false);
		return 1;
	}

	/**
	 * Copies the build's box in {@code level}, the world the build is in, into a clipboard anchored at the
	 * configured origin, writes it and the build to {@link BuildStorage} and tells every client about the
	 * new state of the build.
	 */
	private static Path save(Player actor, LocalSession session, Build build, ServerLevel level) throws WorldEditException, IOException {
		Region region = build.region(level);
		World world = region.getWorld();

		BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
		clipboard.setOrigin(ORIGIN_CORNER.of(region).add(ORIGIN_OFFSET));

		try (EditSession editSession = session.createEditSession(actor)) {
			ForwardExtentCopy copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
			copy.setCopyingEntities(COPY_ENTITIES);
			copy.setCopyingBiomes(COPY_BIOMES);
			Operations.complete(copy);
		}

		Path file = BuildStorage.save(build, clipboard);
		MCVCS.LOGGER.info("{} saved build '{}' v{} from {} at {}", actor.getName(), build.name(), build.version(), world == null ? "its box" : "its box in " + world.getName(), file);
		// Builds are shared, so every client's list just changed; the caller's own selection is sent once it is updated.
		BuildSync.broadcast(level.getServer());
		return file;
	}
}
