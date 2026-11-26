package tony.mcvcs.migration;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildStorage;

/**
 * Turns the {@code build.json} older versions of the mod wrote, when a build belonged to one world, into the one every
 * world shares: the {@code world} field goes, and the placements it listed move under that world's name in
 * {@code placements}. A file without a {@code world} field is already shared and left alone.
 */
final class SharedBuildsMigration implements Migration {
	private static final String WORLD = "world";
	private static final String PLACEMENTS = "placements";

	@Override
	public String description() {
		return "sharing builds between worlds, keying their placements by world";
	}

	@Override
	public void run() throws IOException {
		Path root = BuildStorage.root();
		if (!Files.isDirectory(root)) {
			return;
		}
		List<Path> files;
		try (Stream<Path> children = Files.list(root)) {
			files = children
				.filter(folder -> Build.isValidName(folder.getFileName().toString()))
				.map(folder -> folder.resolve(BuildStorage.BUILD_FILE))
				.filter(Files::isRegularFile)
				.sorted()
				.toList();
		}
		for (Path file : files) {
			migrate(file);
		}
	}

	private static void migrate(Path file) throws IOException {
		JsonObject build;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement json = JsonParser.parseReader(reader);
			if (!json.isJsonObject()) {
				throw new IOException(file + " is not a JSON object");
			}
			build = json.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new IOException("Malformed " + file + ": " + e.getMessage(), e);
		}
		JsonElement world = build.get(WORLD);
		if (world == null) {
			return;
		}
		if (!world.isJsonPrimitive() || !world.getAsJsonPrimitive().isString()) {
			throw new IOException(file + " has a '" + WORLD + "' that is not a string");
		}
		JsonElement placements = build.get(PLACEMENTS);
		if (placements != null && !placements.isJsonObject()) {
			throw new IOException(file + " has '" + PLACEMENTS + "' that are not an object");
		}

		// Every other field stays where it was; the world's placements take the place of the flat ones.
		JsonObject migrated = new JsonObject();
		for (Map.Entry<String, JsonElement> field : build.entrySet()) {
			if (field.getKey().equals(WORLD)) {
				continue;
			}
			if (field.getKey().equals(PLACEMENTS)) {
				migrated.add(PLACEMENTS, byWorld(world.getAsString(), field.getValue().getAsJsonObject()));
			} else {
				migrated.add(field.getKey(), field.getValue());
			}
		}
		if (placements == null) {
			migrated.add(PLACEMENTS, new JsonObject());
		}
		try (Writer writer = Files.newBufferedWriter(file)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(migrated, writer);
		}
		MCVCS.LOGGER.info("Shared {} between worlds, keeping its placements in world '{}'", file, world.getAsString());
	}

	/** {@code placements} as the only world's in a map by world; a world without placements is left out. */
	private static JsonObject byWorld(String world, JsonObject placements) {
		JsonObject byWorld = new JsonObject();
		if (!placements.isEmpty()) {
			byWorld.add(world, placements);
		}
		return byWorld;
	}
}
