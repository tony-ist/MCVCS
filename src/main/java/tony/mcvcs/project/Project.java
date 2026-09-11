package tony.mcvcs.project;

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
 * It is stored on disk by {@link ProjectStorage} next to the schematics of each version, so it must not reference
 * the world itself; {@link #region} turns it back into a WorldEdit region when one is needed.
 *
 * @param name      build name as given to {@code /vcs create}
 * @param world     the save the build belongs to, see {@link #worldOf}
 * @param dimension the dimension of that save the box is in
 * @param box       the world box the build covers
 * @param version   last saved version, starting at 1 for the schematic {@code /vcs create} writes
 */
public record Project(String name, String world, ResourceKey<Level> dimension, ProjectBox box, int version) {
	/**
	 * What a build name may look like. The name becomes the project's folder on disk, so this is the set of
	 * characters a command word may contain minus anything that could name another folder: no {@code .} or
	 * {@code ..} segments, no leading or trailing dots and no separators.
	 */
	public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_+-]+(\\.[A-Za-z0-9_+-]+)*");
	public static final Codec<Project> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(Project::name),
		Codec.STRING.fieldOf("world").forGetter(Project::world),
		ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Project::dimension),
		ProjectBox.CODEC.fieldOf("box").forGetter(Project::box),
		ExtraCodecs.POSITIVE_INT.fieldOf("version").forGetter(Project::version)
	).apply(instance, Project::new));

	/** Whether {@code name} matches {@link #NAME} and so can be used as a project's folder name. */
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

	/** The project as it will be after the next commit. */
	public Project nextVersion() {
		return new Project(name, world, dimension, box, version + 1);
	}

	/** The same build at an earlier (or the same) version, e.g. to locate that version's schematic. */
	public Project atVersion(int version) {
		return new Project(name, world, dimension, box, version);
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
