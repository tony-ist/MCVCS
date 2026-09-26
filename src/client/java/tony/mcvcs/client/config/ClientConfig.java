package tony.mcvcs.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import tony.mcvcs.MCVCS;

/**
 * The client's own settings, in {@code config/mcvcs.json} next to every other mod's config, holding what belongs to
 * neither the key binds screen nor the server: how the builds overlay draws its grid, see
 * {@link tony.mcvcs.client.browser.BuildBrowserScreen}.
 * <pre>
 * {
 *   "rotationSpeed": 36.0,
 *   "cellSize": 96,
 *   "autoDownloadLimit": 1000000
 * }
 * </pre>
 * The file is written with its defaults the first time the client starts without one, so there is something to edit,
 * and read again whenever a world or server is joined, so an edit takes hold without restarting the game. A file
 * that cannot be read is logged and ignored, leaving the settings as they were: a typo in it must not stop the mod
 * from working. Settings the file does not know, such as ones older versions of the mod wrote, are ignored.
 */
public final class ClientConfig {
	/** How fast the previews turn, in degrees per second, unless the config says otherwise: one turn every 10 seconds. */
	public static final double DEFAULT_ROTATION_SPEED = 36.0;
	/** Fastest the previews may turn: two turns a second, past which they only flicker. */
	public static final double MAX_ROTATION_SPEED = 720.0;
	/** Width and height of a preview in the overlay, in GUI pixels, unless the config says otherwise. */
	public static final int DEFAULT_CELL_SIZE = 96;
	/** Smallest preview the config may ask for; below this the name under it no longer fits. */
	public static final int MIN_CELL_SIZE = 48;
	/** Largest preview the config may ask for. */
	public static final int MAX_CELL_SIZE = 512;
	/** Biggest build, in blocks of its box, whose preview is downloaded as soon as the overlay opens, unless the config says otherwise. */
	public static final int DEFAULT_AUTO_DOWNLOAD_LIMIT = 1_000_000;
	/** Where the settings live, {@code config/mcvcs.json} in the game directory. */
	public static final String FILE = "mcvcs.json";

	/**
	 * The settings as the file holds them. A value outside its range is refused by the codec, which leaves the whole
	 * file ignored rather than half applied.
	 *
	 * @param rotationSpeed     how fast the previews in the builds overlay turn, in degrees per second; 0 stops them
	 * @param cellSize          width and height of each preview in the builds overlay, in GUI pixels
	 * @param autoDownloadLimit biggest build, in blocks of its box, whose preview the overlay downloads by itself;
	 *                          bigger ones wait for a click, and 0 makes every one wait
	 */
	public record Settings(double rotationSpeed, int cellSize, int autoDownloadLimit) {
		public static final Settings DEFAULT = new Settings(DEFAULT_ROTATION_SPEED, DEFAULT_CELL_SIZE, DEFAULT_AUTO_DOWNLOAD_LIMIT);
		/** Reading only: a missing setting falls back to its default, so a file need only name what it changes. */
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.doubleRange(0.0, MAX_ROTATION_SPEED).optionalFieldOf("rotationSpeed", DEFAULT_ROTATION_SPEED).forGetter(Settings::rotationSpeed),
			Codec.intRange(MIN_CELL_SIZE, MAX_CELL_SIZE).optionalFieldOf("cellSize", DEFAULT_CELL_SIZE).forGetter(Settings::cellSize),
			Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("autoDownloadLimit", DEFAULT_AUTO_DOWNLOAD_LIMIT).forGetter(Settings::autoDownloadLimit)
		).apply(instance, Settings::new));

		/**
		 * The settings as they are written out, every one of them spelled out. {@link #CODEC} would do the encoding
		 * too, but an optional field whose value is its default is left out of what it writes, so a fresh config
		 * would come out as {@code {}} with nothing in it to change.
		 */
		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("rotationSpeed", rotationSpeed);
			json.addProperty("cellSize", cellSize);
			json.addProperty("autoDownloadLimit", autoDownloadLimit);
			return json;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static volatile Settings settings = Settings.DEFAULT;

	private ClientConfig() {
	}

	/** Reads the settings, writing the file first if there is none, and reads them again on every join. */
	public static void register() {
		load();
		// Editing the file and reconnecting is enough to try a new value out; no restart, and no reading per frame.
		ClientPlayConnectionEvents.INIT.register((handler, client) -> load());
	}

	/** The settings in force right now. */
	public static Settings settings() {
		return settings;
	}

	/** Replaces the settings in force until the file is next read, e.g. to hold the previews still for a test. */
	public static void override(Settings value) {
		settings = value;
	}

	/** Where the settings are read from. */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE);
	}

	/**
	 * Reads the file, keeping the settings as they are if it cannot be read. A missing file is not a fault: it is
	 * written with the defaults so there is something to edit.
	 */
	public static void load() {
		Path file = file();
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement json = JsonParser.parseReader(reader);
			settings = Settings.CODEC.parse(JsonOps.INSTANCE, json)
				.getOrThrow(message -> new IOException("Malformed " + file + ": " + message));
			// A file from an older version lacks the newer settings, or holds ones since dropped: write it out again with
			// exactly the settings there are now, keeping every value it gave, so each one is there to be edited.
			if (!json.getAsJsonObject().keySet().equals(settings.toJson().keySet())) {
				write(file, settings);
			}
		} catch (NoSuchFileException e) {
			settings = Settings.DEFAULT;
			write(file, settings);
		} catch (IOException | JsonParseException e) {
			MCVCS.LOGGER.error("Failed to read {}; keeping the settings as they are", file, e);
		}
	}

	/** Writes {@code value} to {@code file}; a config that cannot be written is logged and otherwise ignored. */
	private static void write(Path file, Settings value) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(value.toJson(), writer);
			}
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to write {}", file, e);
		}
	}
}
