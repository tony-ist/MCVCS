package tony.mcvcs.build;

import java.util.Optional;
import java.util.regex.Pattern;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;

/**
 * A build under version control: the name it was created with, the world and box it was created from and the
 * version most recently written to disk.
 * <p>
 * The box is fixed at {@code /vcs create} time; later changes to the player's WorldEdit selection do not touch it.
 * It is stored on disk by {@link BuildStorage} next to the schematics of each version, so it must not reference
 * the world itself; {@link #region} turns it back into a WorldEdit region when one is needed.
 *
 * @param name      build name as given to {@code /vcs create}
 * @param world     the save the build belongs to, see {@link #worldOf}
 * @param dimension the dimension of that save the box is in
 * @param box       the world box the build covers
 * @param version   last saved version, starting at 1 for the schematic {@code /vcs create} writes
 * @param head      the version the box is expected to hold: the latest one after {@code /vcs create},
 *                  {@code /vcs commit} or {@code /vcs expand}, the one put back by {@code /vcs checkout} after that.
 *                  What the box holds beyond it is uncommitted work, see {@link #atHead}.
 */
public record Build(String name, String world, ResourceKey<Level> dimension, BuildBox box, int version, int head) {
	/**
	 * What a build name may look like. The name becomes the build's folder on disk, so this is the set of
	 * characters a command word may contain minus anything that could name another folder: no {@code .} or
	 * {@code ..} segments, no leading or trailing dots and no separators.
	 */
	public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_+-]+(\\.[A-Za-z0-9_+-]+)*");
	/** Saved with the {@code head} field left out while it is the latest version, which is what a file without one means. */
	public static final Codec<Build> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(Build::name),
		Codec.STRING.fieldOf("world").forGetter(Build::world),
		ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Build::dimension),
		BuildBox.CODEC.fieldOf("box").forGetter(Build::box),
		ExtraCodecs.POSITIVE_INT.fieldOf("version").forGetter(Build::version),
		ExtraCodecs.POSITIVE_INT.optionalFieldOf("head").forGetter(build -> build.head == build.version ? Optional.empty() : Optional.of(build.head))
	).apply(instance, (name, world, dimension, box, version, head) -> new Build(name, world, dimension, box, version, head.orElse(version))));

	public Build {
		if (head > version) {
			throw new IllegalArgumentException("Build '" + name + "' cannot hold v" + head + " when it only has versions 1 to " + version);
		}
	}

	/** A build whose box holds its latest version. */
	public Build(String name, String world, ResourceKey<Level> dimension, BuildBox box, int version) {
		this(name, world, dimension, box, version, version);
	}

	/** Whether {@code name} matches {@link #NAME} and so can be used as a build's folder name. */
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

	/** The build as it will be after the next commit, which its box then holds. */
	public Build nextVersion() {
		return new Build(name, world, dimension, box, version + 1);
	}

	/** The same build at an earlier (or the same) version, e.g. to locate that version's schematic. */
	public Build atVersion(int version) {
		return new Build(name, world, dimension, box, version);
	}

	/** The build at the version its box holds, see {@link #head}. */
	public Build atHead() {
		return atVersion(head);
	}

	/** The build with its box holding {@code head}, as it is after {@code /vcs checkout} of that version. */
	public Build withHead(int head) {
		return new Build(name, world, dimension, box, version, head);
	}

	/** The build's box as a WorldEdit region in {@code level}, which must be the world {@link #dimension} names. */
	public Region region(ServerLevel level) {
		if (!level.dimension().equals(dimension)) {
			throw new IllegalArgumentException("Build '" + name + "' is in " + dimension.identifier() + ", not " + level.dimension().identifier());
		}
		FabricAdapter adapter = FabricAdapter.get();
		return new CuboidRegion(adapter.fromNativeWorld(level), adapter.adapt(box.min()), adapter.adapt(box.max()));
	}
}
