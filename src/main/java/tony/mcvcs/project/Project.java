package tony.mcvcs.project;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;

/**
 * A build under version control: the name it was created with, the box it was created from and the version
 * most recently written to disk.
 * <p>
 * The box is fixed at {@code /vcs create} time; later changes to the player's WorldEdit selection do not touch it.
 * It is stored with the world by {@link ProjectData}, so it must not reference the world itself; {@link #region}
 * turns it back into a WorldEdit region when one is needed.
 *
 * @param name    build name as given to {@code /vcs create}
 * @param box     the world box the build covers
 * @param version last saved version, starting at 1 for the schematic {@code /vcs create} writes
 */
public record Project(String name, ProjectBox box, int version) {
	public static final Codec<Project> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(Project::name),
		ProjectBox.CODEC.fieldOf("box").forGetter(Project::box),
		ExtraCodecs.POSITIVE_INT.fieldOf("version").forGetter(Project::version)
	).apply(instance, Project::new));

	/** The project as it will be after the next commit. */
	public Project nextVersion() {
		return new Project(name, box, version + 1);
	}

	/** The same build at an earlier (or the same) version, e.g. to locate that version's schematic. */
	public Project atVersion(int version) {
		return new Project(name, box, version);
	}

	/** The build's box as a WorldEdit region in {@code level}, the world the project belongs to. */
	public Region region(ServerLevel level) {
		FabricAdapter adapter = FabricAdapter.get();
		return new CuboidRegion(adapter.fromNativeWorld(level), adapter.adapt(box.min()), adapter.adapt(box.max()));
	}

	/** Schematic file name without extension: {@code name} for version 1, {@code name_v2}, {@code name_v3}, ... after. */
	public String fileName() {
		return version == 1 ? name : name + "_v" + version;
	}
}
