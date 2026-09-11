package tony.mcvcs.project;

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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;

/**
 * Everything the mod keeps on disk, under {@code mcvcs/} in the game directory:
 * <pre>
 * mcvcs/
 *   selections.json          which project each player has selected in each world, by world then player UUID
 *   &lt;buildname&gt;/
 *     project.json           the {@link Project}: its world, dimension, box and latest version
 *     v1.schem, v2.schem ... one schematic per version
 * </pre>
 * Every project has a folder of its own and none of it mixes with WorldEdit's {@code //schem} files. The folder
 * is shared by every world in the game directory, so each project records the world it belongs to and lookups
 * filter on it; a name can only be taken by one world at a time. Nothing is cached: each call reads or writes
 * the files, so the folder is the single source of truth and can be edited or copied between game directories
 * while the server is down.
 * <p>
 * Names reach the file system as directory names, so only {@link Project#isValidName valid names} may be stored.
 */
public final class ProjectStorage {
	/** Directory under the game directory holding one folder per project. */
	public static final String ROOT = "mcvcs";
	/** File in each project's folder describing the project. */
	public static final String PROJECT_FILE = "project.json";
	/** File under {@link #ROOT} holding every player's selection in every world. */
	public static final String SELECTIONS_FILE = "selections.json";
	/** Schematic file format. */
	public static final ClipboardFormat FORMAT = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC;

	/** World name to player UUID to build name. */
	private static final Codec<Map<String, Map<UUID, String>>> SELECTIONS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING));
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ProjectStorage() {
	}

	/** The folder holding every project's folder. */
	public static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve(ROOT);
	}

	/** The folder holding every version of the project called {@code name}. */
	public static Path directory(String name) {
		if (!Project.isValidName(name)) {
			throw new IllegalArgumentException("Invalid build name '" + name + "'");
		}
		return root().resolve(name);
	}

	/** The file describing the project called {@code name}. */
	public static Path projectFile(String name) {
		return directory(name).resolve(PROJECT_FILE);
	}

	/** The schematic of {@code project} at its version. */
	public static Path schematicFile(Project project) {
		return directory(project.name()).resolve("v" + project.version() + "." + FORMAT.getPrimaryFileExtension());
	}

	/** The file recording every player's selected project in every world. */
	public static Path selectionsFile() {
		return root().resolve(SELECTIONS_FILE);
	}

	/** Names of every project belonging to {@code world} that has a folder with a {@link #PROJECT_FILE} in it, sorted. */
	public static List<String> names(String world) throws IOException {
		if (!Files.isDirectory(root())) {
			return List.of();
		}
		List<String> names;
		try (Stream<Path> folders = Files.list(root())) {
			names = folders
				.filter(folder -> Files.isRegularFile(folder.resolve(PROJECT_FILE)))
				.map(folder -> folder.getFileName().toString())
				.filter(Project::isValidName)
				.sorted()
				.toList();
		}
		List<String> inWorld = new ArrayList<>();
		for (String name : names) {
			if (readJson(projectFile(name), Project.CODEC).world().equals(world)) {
				inWorld.add(name);
			}
		}
		return List.copyOf(inWorld);
	}

	/** The project called {@code name} in whichever world it belongs to, if it has a folder with a {@link #PROJECT_FILE} in it. */
	public static Optional<Project> findInAnyWorld(String name) throws IOException {
		if (!Project.isValidName(name)) {
			return Optional.empty();
		}
		Path file = projectFile(name);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		return Optional.of(readJson(file, Project.CODEC));
	}

	/** The project called {@code name}, if it exists and belongs to {@code world}. */
	public static Optional<Project> find(String world, String name) throws IOException {
		return findInAnyWorld(name).filter(project -> project.world().equals(world));
	}

	/**
	 * Writes {@code clipboard} as the schematic of {@code project} at its version and records the project itself as
	 * the latest state of the build with its name, creating the project's folder if needed.
	 *
	 * @return the schematic written
	 */
	public static Path save(Project project, Clipboard clipboard) throws IOException {
		Path file = schematicFile(project);
		Files.createDirectories(file.getParent());

		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
			 ClipboardWriter writer = FORMAT.getWriter(out)) {
			writer.write(clipboard);
		}
		writeJson(projectFile(project.name()), Project.CODEC, project);
		return file;
	}

	/**
	 * Reads the schematic {@link #save} wrote for {@code project} at its version.
	 *
	 * @throws NoSuchFileException if no schematic was written for that version
	 */
	public static Clipboard read(Project project) throws IOException {
		Path file = schematicFile(project);
		if (!Files.isRegularFile(file)) {
			throw new NoSuchFileException(file.toString());
		}

		try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
			 ClipboardReader reader = FORMAT.getReader(in)) {
			return reader.read();
		}
	}

	/** The name of the project {@code player} has selected in {@code world}, if any is recorded. */
	public static Optional<String> selection(String world, UUID player) throws IOException {
		return Optional.ofNullable(selections().getOrDefault(world, Map.of()).get(player));
	}

	/** Records {@code name} as the project {@code player} has selected in {@code world}, leaving every other selection alone. */
	public static void saveSelection(String world, UUID player, String name) throws IOException {
		Map<String, Map<UUID, String>> selections = new HashMap<>(selections());
		Map<UUID, String> inWorld = new HashMap<>(selections.getOrDefault(world, Map.of()));
		inWorld.put(player, name);
		selections.put(world, inWorld);

		Files.createDirectories(root());
		writeJson(selectionsFile(), SELECTIONS_CODEC, selections);
	}

	private static Map<String, Map<UUID, String>> selections() throws IOException {
		Path file = selectionsFile();
		return Files.isRegularFile(file) ? readJson(file, SELECTIONS_CODEC) : Map.of();
	}

	private static <T> T readJson(Path file, Codec<T> codec) throws IOException {
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement json = JsonParser.parseReader(reader);
			return codec.parse(JsonOps.INSTANCE, json).getOrThrow(message -> new IOException("Malformed " + file + ": " + message));
		} catch (JsonParseException e) {
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
