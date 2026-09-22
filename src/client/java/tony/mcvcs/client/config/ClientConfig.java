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
 * neither the key binds screen nor the server: how far a sprinting key press moves the copy {@code /vcs place} is
 * showing, see {@link tony.mcvcs.client.place.PlacePreviewKeys}.
 * <pre>
 * {
 *   "sprintStep": 10
 * }
 * </pre>
 * The file is written with its defaults the first time the client starts without one, so there is something to edit,
 * and read again whenever a world or server is joined, so an edit takes hold without restarting the game. A file
 * that cannot be read is logged and ignored, leaving the settings as they were: a typo in it must not stop the mod
 * from working.
 */
public final class ClientConfig {
	/** How far a press moves the copy while the sprint key is held, unless the config says otherwise. */
	public static final int DEFAULT_SPRINT_STEP = 10;
	/** Largest step the file may ask for; beyond this a press would throw the copy out of sight. */
	public static final int MAX_SPRINT_STEP = 1000;
	/** Where the settings live, {@code config/mcvcs.json} in the game directory. */
	public static final String FILE = "mcvcs.json";

	/**
	 * The settings as the file holds them. A value outside its range is refused by the codec, which leaves the whole
	 * file ignored rather than half applied.
	 *
	 * @param sprintStep blocks a placement preview moves per key press while the sprint key is held
	 */
	public record Settings(int sprintStep) {
		public static final Settings DEFAULT = new Settings(DEFAULT_SPRINT_STEP);
		/** Reading only: a missing setting falls back to its default, so a file need only name what it changes. */
		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.intRange(1, MAX_SPRINT_STEP).optionalFieldOf("sprintStep", DEFAULT_SPRINT_STEP).forGetter(Settings::sprintStep)
		).apply(instance, Settings::new));

		/**
		 * The settings as they are written out, every one of them spelled out. {@link #CODEC} would do the encoding
		 * too, but an optional field whose value is its default is left out of what it writes, so a fresh config
		 * would come out as {@code {}} with nothing in it to change.
		 */
		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("sprintStep", sprintStep);
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

	/** How far a press moves a placement preview while the sprint key is held. */
	public static int sprintStep() {
		return settings.sprintStep();
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
