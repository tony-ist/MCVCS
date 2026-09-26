package tony.mcvcs.build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import org.jspecify.annotations.Nullable;

/**
 * Everything the mod keeps on disk, under {@code mcvcs/} in the game directory:
 * <pre>
 * mcvcs/
 *   selections.json          which placement each player has selected in each world, by world then player UUID
 *   &lt;buildname&gt;/
 *     build.json             the {@link Build}: its world, every version's extent and every placement of it
 *     &lt;buildname&gt;-v1.schem, &lt;buildname&gt;-v2.schem ... one schematic per version * </pre>
 * Every build has a folder of its own and none of it mixes with WorldEdit's {@code //schem} files. The folder
 * is shared by every world in the game directory, so each build records the world it belongs to and lookups
 * filter on it; a name can only be taken by one world at a time. Nothing is cached: each call reads or writes
 * the files, so the folder is the single source of truth and can be edited or copied between game directories
 * while the server is down.
 * <p>
 * A schematic still carries the world position it was copied from, which is what {@code /vcs load} pastes by, but
 * where a version belongs at a placement comes from {@code build.json} alone, see {@link Build}.
 * <p>
 * Names reach the file system as directory names, so only {@link Build#isValidName valid names} may be stored.
 */
public final class BuildStorage {
	/** Directory under the game directory holding one folder per build. */
	public static final String ROOT = "mcvcs";
	/** File in each build's folder describing the build. */
	public static final String BUILD_FILE = "build.json";
	/** File under {@link #ROOT} holding every player's selection in every world. */
	public static final String SELECTIONS_FILE = "selections.json";
	/** Schematic file format. */
	public static final ClipboardFormat FORMAT = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC;

	/** The build and placement a player has selected in one world. */
	public record Selection(String build, String placement) {
		public static final Codec<Selection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("build").forGetter(Selection::build),
			Codec.STRING.fieldOf("placement").forGetter(Selection::placement)
		).apply(instance, Selection::new));
	}

	/** World name to player UUID to what they have selected there. */
	private static final Codec<Map<String, Map<UUID, Selection>>> SELECTIONS_CODEC =
		Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(UUIDUtil.STRING_CODEC, Selection.CODEC));
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private BuildStorage() {
	}

	/** The folder holding every build's folder. */
	public static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve(ROOT);
	}

	/** The folder holding every version of the build called {@code name}. */
	public static Path directory(String name) {
		if (!Build.isValidName(name)) {
			throw new IllegalArgumentException("Invalid build name '" + name + "'");
		}
		return root().resolve(name);
	}

	/** The file describing the build called {@code name}. */
	public static Path buildFile(String name) {
		return directory(name).resolve(BUILD_FILE);
	}

	/** The schematic of version {@code version} of the build called {@code name}. */
	public static Path schematicFile(String name, int version) {
		return directory(name).resolve(name + "-v" + version + "." + FORMAT.getPrimaryFileExtension());
	}

	/** The file recording every player's selection in every world. */
	public static Path selectionsFile() {
		return root().resolve(SELECTIONS_FILE);
	}

	/** Every build belonging to {@code world} that has a folder with a {@link #BUILD_FILE} in it, sorted by name. */
	public static List<Build> all(String world) throws IOException {
		List<Build> inWorld = new ArrayList<>();
		for (String name : folderNames()) {
			Build build = readBuild(name);
			if (build.world().equals(world)) {
				inWorld.add(build);
			}
		}
		return List.copyOf(inWorld);
	}

	/** The names of every build folder under {@link #ROOT} that has a {@link #BUILD_FILE} in it, sorted. */
	private static List<String> folderNames() throws IOException {
		if (!Files.isDirectory(root())) {
			return List.of();
		}
		try (Stream<Path> folders = Files.list(root())) {
			return folders
				.filter(folder -> Files.isRegularFile(folder.resolve(BUILD_FILE)))
				.map(folder -> folder.getFileName().toString())
				.filter(Build::isValidName)
				.sorted()
				.toList();
		}
	}

	/** Names of every build belonging to {@code world} that has a folder with a {@link #BUILD_FILE} in it, sorted. */
	public static List<String> names(String world) throws IOException {
		return all(world).stream().map(Build::name).toList();
	}

	/** The build called {@code name} in whichever world it belongs to, if it has a folder with a {@link #BUILD_FILE} in it. */
	public static Optional<Build> findInAnyWorld(String name) throws IOException {
		if (!Build.isValidName(name)) {
			return Optional.empty();
		}
		if (!Files.isRegularFile(buildFile(name))) {
			return Optional.empty();
		}
		return Optional.of(readBuild(name));
	}

	/**
	 * The build whose name is {@code name} up to letter case, in whichever world it belongs to, if it has a folder with
	 * a {@link #BUILD_FILE} in it. Build folders sit on file systems that may or may not tell {@code Foo} from
	 * {@code foo}, so a new name must differ from every existing one by more than case.
	 */
	public static Optional<Build> findInAnyWorldIgnoringCase(String name) throws IOException {
		for (String existing : folderNames()) {
			if (existing.equalsIgnoreCase(name)) {
				return Optional.of(readBuild(existing));
			}
		}
		return Optional.empty();
	}

	/** The build called {@code name}, if it exists and belongs to {@code world}. */
	public static Optional<Build> find(String world, String name) throws IOException {
		return findInAnyWorld(name).filter(build -> build.world().equals(world));
	}

	/** The build called {@code name}, reading its {@link #BUILD_FILE}. */
	private static Build readBuild(String name) throws IOException {
		Path file = buildFile(name);
		return parse(file, Build.CODEC, readJson(file));
	}

	/**
	 * Writes {@code clipboard} as the schematic of version {@code version} of {@code build} and records the build
	 * itself as the latest state of the build with its name, creating the build's folder if needed.
	 *
	 * @return the schematic written
	 */
	public static Path save(Build build, int version, Clipboard clipboard) throws IOException {
		Path file = schematicFile(build.name(), version);
		Files.createDirectories(file.getParent());

		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
			 ClipboardWriter writer = FORMAT.getWriter(out)) {
			writer.write(clipboard);
		}
		writeJson(buildFile(build.name()), Build.CODEC, build);
		return file;
	}

	/**
	 * Records {@code build} as the latest state of the build with its name without writing any schematic, for
	 * changes such as {@code /vcs checkout} moving a placement's head or {@code /vcs place} adding one. The build's
	 * folder must exist already.
	 */
	public static void update(Build build) throws IOException {
		Path file = buildFile(build.name());
		if (!Files.isRegularFile(file)) {
			throw new NoSuchFileException(file.toString());
		}
		writeJson(file, Build.CODEC, build);
	}

	/**
	 * Removes the build called {@code name} from disk: its folder with every version in it, and every player's
	 * selection of it in any world, so nothing is left pointing at the build. Nothing happens if there is no such folder.
	 */
	public static void delete(String name) throws IOException {
		Path directory = directory(name);
		if (Files.exists(directory)) {
			// Files.delete needs empty folders, so children go first.
			try (Stream<Path> files = Files.walk(directory)) {
				for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(file);
				}
			}
		}
		clearSelectionsOf(name, null);
	}

	/**
	 * Reads the schematic {@link #save} wrote for version {@code version} of the build called {@code name}.
	 *
	 * @throws NoSuchFileException if no schematic was written for that version
	 */
	public static Clipboard readSchematic(String name, int version) throws IOException {
		Path file = schematicFile(name, version);
		if (!Files.isRegularFile(file)) {
			throw new NoSuchFileException(file.toString());
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
			 ClipboardReader reader = FORMAT.getReader(in)) {
			return reader.read();
		}
	}

	/** What {@code player} has selected in {@code world}, if anything is recorded. */
	public static Optional<Selection> selection(String world, UUID player) throws IOException {
		return Optional.ofNullable(selections().getOrDefault(world, Map.of()).get(player));
	}

	/** Records {@code selection} as what {@code player} has selected in {@code world}, leaving every other selection alone. */
	public static void saveSelection(String world, UUID player, Selection selection) throws IOException {
		Map<String, Map<UUID, Selection>> selections = new HashMap<>(selections());
		Map<UUID, Selection> inWorld = new HashMap<>(selections.getOrDefault(world, Map.of()));
		inWorld.put(player, selection);
		selections.put(world, inWorld);

		Files.createDirectories(root());
		writeJson(selectionsFile(), SELECTIONS_CODEC, selections);
	}

	/** Forgets what {@code player} has selected in {@code world}, leaving every other selection alone. */
	public static void clearSelection(String world, UUID player) throws IOException {
		Map<String, Map<UUID, Selection>> selections = new HashMap<>(selections());
		Map<UUID, Selection> inWorld = new HashMap<>(selections.getOrDefault(world, Map.of()));
		if (inWorld.remove(player) == null) {
			return;
		}
		if (inWorld.isEmpty()) {
			selections.remove(world);
		} else {
			selections.put(world, inWorld);
		}

		Files.createDirectories(root());
		writeJson(selectionsFile(), SELECTIONS_CODEC, selections);
	}

	/**
	 * Forgets every player's selection of the build called {@code name} in every world, or only of its placement
	 * called {@code placement} when one is given, leaving every other selection alone.
	 */
	public static void clearSelectionsOf(String name, @Nullable String placement) throws IOException {
		Map<String, Map<UUID, Selection>> selections = new HashMap<>();
		boolean changed = false;
		for (Map.Entry<String, Map<UUID, Selection>> world : selections().entrySet()) {
			Map<UUID, Selection> inWorld = new HashMap<>(world.getValue());
			changed |= inWorld.values().removeIf(selection -> selection.build().equals(name)
				&& (placement == null || selection.placement().equals(placement)));
			if (!inWorld.isEmpty()) {
				selections.put(world.getKey(), inWorld);
			}
		}
		if (!changed) {
			return;
		}

		Files.createDirectories(root());
		writeJson(selectionsFile(), SELECTIONS_CODEC, selections);
	}

	/** Every player's selection in every world, as {@link #SELECTIONS_FILE} records it. */
	private static Map<String, Map<UUID, Selection>> selections() throws IOException {
		Path file = selectionsFile();
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		return parse(file, SELECTIONS_CODEC, readJson(file));
	}

	private static JsonElement readJson(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file)) {
			return JsonParser.parseReader(reader);
		} catch (JsonParseException e) {
			throw new IOException("Malformed " + file + ": " + e.getMessage(), e);
		}
	}

	private static <T> T parse(Path file, Codec<T> codec, JsonElement json) throws IOException {
		try {
			return codec.parse(JsonOps.INSTANCE, json).getOrThrow(message -> new IOException("Malformed " + file + ": " + message));
		} catch (IllegalArgumentException e) {
			// A record whose fields do not go together, such as a head past the latest version, is refused by its constructor.
			throw new IOException("Malformed " + file + ": " + e.getMessage(), e);
		}
	}

	private static <T> void writeJson(Path file, Codec<T> codec, T value) throws IOException {
		JsonElement json = codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow(message -> new IOException("Cannot encode " + file + ": " + message));
		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(json, writer);
		}
	}
}
