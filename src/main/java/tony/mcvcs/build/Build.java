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
 * @param tags       the version each tag names, by tag, e.g. {@code 2.0.0 -> 2}; a version may have any number of tags
 *                   but a tag names only one version, see {@link #TAG}
 */
public record Build(String name, String world, int version, Map<Integer, BuildBox> versions, Map<String, Placement> placements, Map<String, Integer> tags) {
	/**
	 * What a build or placement name may look like. The build name becomes the build's folder on disk, so this is
	 * the set of characters a command word may contain minus anything that could name another folder: no {@code .}
	 * or {@code ..} segments, no leading or trailing dots and no separators. Nor may it start with {@code -}, which
	 * would read as a flag such as {@code -we} or {@code -h}.
	 */
	public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_+][A-Za-z0-9_+-]*(\\.[A-Za-z0-9_+-]+)*");
	/**
	 * What a version tag may look like, e.g. {@code 2.0.0} or {@code 1.5.4-rc+tick_net}: letters, digits, {@code -},
	 * {@code _}, {@code +} and {@code .}, which are also exactly what a command word may contain unquoted. Commands take
	 * a tag wherever they take a version number, so a tag may not be digits alone, which would read as one.
	 */
	public static final Pattern TAG = Pattern.compile("(?![0-9]+$)[A-Za-z0-9_+.-]+");
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
		Codec.unboundedMap(Codec.STRING, Placement.CODEC).fieldOf("placements").forGetter(Build::placements),
		// Optional so that build.json written before tags existed still loads.
		Codec.unboundedMap(Codec.STRING, ExtraCodecs.POSITIVE_INT).optionalFieldOf("tags", Map.of()).forGetter(Build::tags)
	).apply(instance, Build::new));

	public Build {
		// Sorted so that build.json reads in order, however the maps were put together; an immutable copy that keeps
		// that order, which Map.copyOf does not promise.
		versions = sorted(versions);
		placements = sorted(placements);
		tags = sorted(tags);
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
		for (Map.Entry<String, Integer> tag : tags.entrySet()) {
			if (!isValidTag(tag.getKey())) {
				throw new IllegalArgumentException("Invalid tag '" + tag.getKey() + "' in build '" + name + "'");
			}
			if (tag.getValue() > version) {
				throw new IllegalArgumentException("Tag '" + tag.getKey() + "' cannot name v" + tag.getValue()
					+ " when build '" + name + "' only has versions 1 to " + version);
			}
		}
	}

	/** A build with no tags yet, as {@code /vcs create} makes one. */
	public Build(String name, String world, int version, Map<Integer, BuildBox> versions, Map<String, Placement> placements) {
		this(name, world, version, versions, placements, Map.of());
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
	 * Whether a new build or placement may be called {@code name}: a {@link #isValidName valid name} that starts with a
	 * letter from {@code a} to {@code z}, in either case. Only checked when a build or placement is made, so ones named
	 * before the rule still load.
	 */
	public static boolean isValidNewName(String name) {
		char first = name.isEmpty() ? 0 : Character.toLowerCase(name.charAt(0));
		return first >= 'a' && first <= 'z' && isValidName(name);
	}

	/** Whether {@code tag} matches {@link #TAG} and so can be given to a version. */
	public static boolean isValidTag(String tag) {
		return TAG.matcher(tag).matches();
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
		return new Build(this.name, world, version, versions, updated, tags);
	}

	/** The build without the placement called {@code name}, as {@code /vcs unplace} leaves it. */
	public Build withoutPlacement(String name) {
		Map<String, Placement> updated = new LinkedHashMap<>(placements);
		updated.remove(name);
		return new Build(this.name, world, version, versions, updated, tags);
	}

	/**
	 * The build as it is once the next version has been committed with {@code extent}: the version number goes up by
	 * one and that extent is recorded for it. No placement's head moves, so the committing placement has to be given
	 * its new head with {@link #withPlacement}.
	 */
	public Build withNextVersion(BuildBox extent) {
		Map<Integer, BuildBox> updated = new TreeMap<>(versions);
		updated.put(version + 1, extent);
		return new Build(name, world, version + 1, updated, placements, tags);
	}

	/** The version {@code tag} names, if it names one. */
	public Optional<Integer> taggedVersion(String tag) {
		return Optional.ofNullable(tags.get(tag));
	}

	/** Every tag of {@code version}, sorted; empty if it has none. */
	public List<String> tagsOf(int version) {
		return tags.entrySet().stream().filter(tag -> tag.getValue() == version).map(Map.Entry::getKey).toList();
	}

	/** {@code version} as chat shows it: {@code v2}, followed by its tags if it has any, e.g. {@code v2 (2.0.0, stable)}. */
	public String versionLabel(int version) {
		List<String> tagsOf = tagsOf(version);
		return "v" + version + (tagsOf.isEmpty() ? "" : " (" + String.join(", ", tagsOf) + ")");
	}

	/** The build with {@code tag} naming {@code version}, which must be one of its versions; the tag is moved if it named another. */
	public Build withTag(String tag, int version) {
		Map<String, Integer> updated = new LinkedHashMap<>(tags);
		updated.put(tag, version);
		return new Build(name, world, this.version, versions, placements, updated);
	}
}
