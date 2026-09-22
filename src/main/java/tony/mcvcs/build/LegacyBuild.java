package tony.mcvcs.build;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.Level;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import tony.mcvcs.MCVCS;

/**
 * The {@code build.json} of the version of this mod before placements, and its one-time conversion to the current
 * one. It described a build with a single box in a single dimension, the one {@code /vcs create} made:
 * <pre>
 * {"name": "tower", "world": "New World", "dimension": "minecraft:overworld",
 *  "box": {"min": [100, 64, -30], "max": [107, 76, -23]}, "version": 7, "head": 5}
 * </pre>
 * which becomes a build whose versions are held in build space and whose one placement, called {@link Build#MAIN},
 * stands where that box was.
 * <p>
 * Nothing but BuildStorage reaches in here when it reads a build, and only for a file that still has a {@code box} field, so
 * this whole class and the one call to {@link #migrate} can be deleted once no such file is left.
 */
record LegacyBuild(String name, String world, ResourceKey<Level> dimension, BuildBox box, int version, int head) {
	/** The field that tells a file written before placements from one written after. */
	static final String MARKER_FIELD = "box";

	private static final Codec<LegacyBuild> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(LegacyBuild::name),
		Codec.STRING.fieldOf("world").forGetter(LegacyBuild::world),
		ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(LegacyBuild::dimension),
		BuildBox.CODEC.fieldOf(MARKER_FIELD).forGetter(LegacyBuild::box),
		ExtraCodecs.POSITIVE_INT.fieldOf("version").forGetter(LegacyBuild::version),
		ExtraCodecs.POSITIVE_INT.optionalFieldOf("head").forGetter(build -> build.head == build.version ? Optional.empty() : Optional.of(build.head))
	).apply(instance, (name, world, dimension, box, version, head) -> new LegacyBuild(name, world, dimension, box, version, head.orElse(version))));

	/** Whether {@code json} is a {@code build.json} written before placements and so has to be {@link #migrate}d. */
	static boolean isLegacy(JsonElement json) {
		return json.isJsonObject() && ((JsonObject) json).has(MARKER_FIELD);
	}

	/**
	 * The build {@code json} described, in the current form. Every version's extent is taken from its schematic,
	 * which records the world box it was copied from, and written in build space, where version 1's minimum corner
	 * is the origin; the one placement keeps the dimension, the world position and the head the file had.
	 *
	 * @throws IOException if the file is malformed or a version's schematic is missing or unreadable
	 */
	static Build migrate(JsonElement json) throws IOException {
		LegacyBuild legacy = CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(message -> new IOException("Malformed build before placements: " + message));

		// Build space starts at version 1's minimum corner, so that is where the one placement's origin goes.
		Clipboard first = BuildStorage.readSchematic(legacy.name, 1);
		BuildBox origin = BuildBox.of(first.getRegion());
		Map<Integer, BuildBox> versions = new LinkedHashMap<>();
		for (int version = 1; version <= legacy.version; version++) {
			BuildBox covered = version == 1 ? origin : BuildBox.of(BuildStorage.readSchematic(legacy.name, version).getRegion());
			versions.put(version, covered.relativeTo(origin.min()));
		}

		Build migrated = new Build(legacy.name, legacy.world, legacy.version, versions,
			Map.of(Build.MAIN, new Placement(legacy.dimension, origin.min(), legacy.head)));
		MCVCS.LOGGER.info("Migrated build '{}' with {} versions to placements; its box became placement '{}' at {}",
			legacy.name, legacy.version, Build.MAIN, origin.min().toShortString());
		return migrated;
	}
}
