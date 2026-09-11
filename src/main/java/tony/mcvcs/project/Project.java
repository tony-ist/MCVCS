package tony.mcvcs.project;

import com.sk89q.worldedit.regions.Region;

/**
 * A build under version control: the name it was created with, the region it was created from and the version
 * most recently written to disk.
 * <p>
 * The region is fixed at {@code /vcs create} time; later changes to the player's WorldEdit selection do not touch it.
 *
 * @param name    build name as given to {@code /vcs create}
 * @param region  the world region the build covers
 * @param version last saved version, starting at 1 for the schematic {@code /vcs create} writes
 */
public record Project(String name, Region region, int version) {
	/** The project as it will be after the next commit. */
	public Project nextVersion() {
		return new Project(name, region, version + 1);
	}

	/** Schematic file name without extension: {@code name} for version 1, {@code name_v2}, {@code name_v3}, ... after. */
	public String fileName() {
		return version == 1 ? name : name + "_v" + version;
	}
}
