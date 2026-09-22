package tony.mcvcs.build;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A build under version control: the name it was created with, the world it belongs to, the extent of every version
 * saved for it and the placements of it standing in that world.
 * <p>
 * Versions are shared by every placement: a commit from any of them saves the build's next version, and any of them
 * can then check that version out. What differs between placements is where they stand and which version they hold,
 * see {@link Placement}.
 * <p>
 * Geometry is kept in <em>build space</em>, the build's own coordinates, in which version 1's minimum corner is
 * {@code (0, 0, 0)}: {@link #versions} holds each version's extent there, and a placement turns build space into
 * world positions with its {@link Placement#origin}. Nothing here references a world, so it can all be stored on
 * disk by {@link BuildStorage} next to the schematics.
 *
 * @param name       build name as given to {@code /vcs create}
 * @param world      the save the build belongs to, see {@link #worldOf}
 * @param version    last saved version, starting at 1 for the schematic {@code /vcs create} writes
 * @param versions   the extent of every version from 1 to {@link #version}, in build space
 * @param placements every placement of the build standing in the world, by name; may be empty once they have all
 *                   been {@code /vcs unplace}d, which leaves the versions on disk
 */
public record Build(String name, String world, int version, Map<Integer, BuildBox> versions, Map<String, Placement> placements) {
	/**
	 * What a build or placement name may look like. The build name becomes the build's folder on disk, so this is
	 * the set of characters a command word may contain minus anything that could name another folder: no {@code .}
	 * or {@code ..} segments, no leading or trailing dots and no separators.
	 */
	public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_+-]+(\\.[A-Za-z0-9_+-]+)*");
	/** Name {@code /vcs create} gives the placement it makes, unless another is asked for. */
	public static final String MAIN = "main";
	/** What {@code /vcs place} names a placement when it is given no name: this followed by the lowest free number. */
	public static final String PLACEMENT_PREFIX = "p";
	/** What separates a build's name from a placement's in chat and in the labels floating over the world. */
	public static final String LABEL_SEPARATOR = "/";

	/** Version numbers are map keys, so they are written as the strings JSON needs. */
	private static final Codec<Integer> VERSION_KEY = Codec.STRING.comapFlatMap(key -> {
		try {
			int version = Integer.parseInt(key);
			return version > 0 ? DataResult.success(version) : DataResult.error(() -> "Version '" + key + "' is not positive");
		} catch (NumberFormatException e) {
			return DataResult.error(() -> "Version '" + key + "' is not a number");
		}
	}, String::valueOf);
	public static final Codec<Build> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(Build::name),
		Codec.STRING.fieldOf("world").forGetter(Build::world),
		ExtraCodecs.POSITIVE_INT.fieldOf("version").forGetter(Build::version),
		Codec.unboundedMap(VERSION_KEY, BuildBox.CODEC).fieldOf("versions").forGetter(Build::versions),
		Codec.unboundedMap(Codec.STRING, Placement.CODEC).fieldOf("placements").forGetter(Build::placements)
	).apply(instance, Build::new));

	public Build {
		// Sorted so that build.json reads in order, however the maps were put together; an immutable copy that keeps
		// that order, which Map.copyOf does not promise.
		versions = sorted(versions);
		placements = sorted(placements);
		for (int v = 1; v <= version; v++) {
			if (!versions.containsKey(v)) {
				throw new IllegalArgumentException("Build '" + name + "' has versions 1 to " + version + " but no extent for v" + v);
			}
		}
		if (versions.size() != version) {
			throw new IllegalArgumentException("Build '" + name + "' has versions 1 to " + version + " but extents for " + versions.keySet());
		}
		for (Map.Entry<String, Placement> placement : placements.entrySet()) {
			if (!isValidName(placement.getKey())) {
				throw new IllegalArgumentException("Invalid placement name '" + placement.getKey() + "' in build '" + name + "'");
			}
			if (placement.getValue().head() > version) {
				throw new IllegalArgumentException("Placement '" + placement.getKey() + "' cannot hold v" + placement.getValue().head()
					+ " when build '" + name + "' only has versions 1 to " + version);
			}
		}
	}

	/** An unmodifiable copy of {@code map} in the order of its keys, so the JSON written from it is stable. */
	private static <K extends Comparable<K>, V> Map<K, V> sorted(Map<K, V> map) {
		Map<K, V> sorted = new LinkedHashMap<>();
		map.keySet().stream().sorted().forEach(key -> sorted.put(key, map.get(key)));
		return Collections.unmodifiableMap(sorted);
	}

	/** Whether {@code name} matches {@link #NAME} and so can be used as a build's folder name or a placement's name. */
	public static boolean isValidName(String name) {
		return NAME.matcher(name).matches();
	}

	/**
	 * What identifies the world {@code server} runs as a {@link #world}: the name of its save folder, which is unique
	 * within a game directory and survives reopening the world. In singleplayer that is the folder under
	 * {@code saves/}; on a dedicated server it is {@code level-name}.
	 */
	public static String worldOf(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
	}

	/** Whether the build belongs to the world {@code server} runs. */
	public boolean isIn(MinecraftServer server) {
		return world.equals(worldOf(server));
	}

	/** The extent of {@code version} in build space, which every placement lays at its own origin. */
	public BuildBox extent(int version) {
		BuildBox extent = versions.get(version);
		if (extent == null) {
			throw new IllegalArgumentException("Build '" + name + "' has no v" + version + "; it has versions 1 to " + this.version);
		}
		return extent;
	}

	/** Whether {@code version} is one of the build's, so it can be checked out, previewed or diffed against. */
	public boolean hasVersion(int version) {
		return versions.containsKey(version);
	}

	/** Every version number, in order, e.g. for command suggestions. */
	public List<Integer> versionNumbers() {
		return IntStream.rangeClosed(1, version).boxed().toList();
	}

	/** The placement called {@code name}, if the build has one. */
	public Optional<Placement> placement(String name) {
		return Optional.ofNullable(placements.get(name));
	}

	/** Every placement's name, sorted. */
	public List<String> placementNames() {
		return List.copyOf(placements.keySet());
	}

	/**
	 * The placement a command acts on when it is given a build but no placement: {@link #MAIN} if the build still
	 * has one, otherwise the first by name; empty only while the build has no placement at all.
	 */
	public Optional<String> defaultPlacementName() {
		if (placements.containsKey(MAIN)) {
			return Optional.of(MAIN);
		}
		return placements.keySet().stream().findFirst();
	}

	/** A placement name this build has no placement under: {@link #PLACEMENT_PREFIX} and the lowest free number from 2 up. */
	public String freePlacementName() {
		for (int n = 2; ; n++) {
			String candidate = PLACEMENT_PREFIX + n;
			if (!placements.containsKey(candidate)) {
				return candidate;
			}
		}
	}

	/** The build with {@code placement} under {@code name}, replacing any placement of that name. */
	public Build withPlacement(String name, Placement placement) {
		Map<String, Placement> updated = new LinkedHashMap<>(placements);
		updated.put(name, placement);
		return new Build(this.name, world, version, versions, updated);
	}

	/** The build without the placement called {@code name}, as {@code /vcs unplace} leaves it. */
	public Build withoutPlacement(String name) {
		Map<String, Placement> updated = new LinkedHashMap<>(placements);
		updated.remove(name);
		return new Build(this.name, world, version, versions, updated);
	}

	/**
	 * The build as it is once the next version has been committed with {@code extent}: the version number goes up by
	 * one and that extent is recorded for it. No placement's head moves, so the committing placement has to be given
	 * its new head with {@link #withPlacement}.
	 */
	public Build withNextVersion(BuildBox extent) {
		Map<Integer, BuildBox> updated = new TreeMap<>(versions);
		updated.put(version + 1, extent);
		return new Build(name, world, version + 1, updated, placements);
	}
}
