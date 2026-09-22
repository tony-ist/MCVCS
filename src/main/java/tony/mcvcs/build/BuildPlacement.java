package tony.mcvcs.build;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A {@link Placement} together with the {@link Build} it belongs to and the name it is filed under: what every
 * command acts on once a placement is selected, and what a punch of a block or the client's hotkey picks out.
 * <p>
 * Nothing here is stored; it is put together from a build whenever one is read. Build space is turned into world
 * positions here and nowhere else: {@link #box} is the placement's current box and {@link #boxOf} the box any other
 * version would take at this placement.
 *
 * @param build     the build the placement is of
 * @param name      the placement's name within the build, e.g. {@code main}
 * @param placement where it stands and what it holds
 */
public record BuildPlacement(Build build, String name, Placement placement) {
	/** The placement of {@code build} called {@code name}, if it has one. */
	public static Optional<BuildPlacement> of(Build build, String name) {
		return build.placement(name).map(placement -> new BuildPlacement(build, name, placement));
	}

	/** Every placement of {@code build}, sorted by name. */
	public static List<BuildPlacement> allOf(Build build) {
		return build.placementNames().stream().map(name -> new BuildPlacement(build, name, build.placements().get(name))).toList();
	}

	/** How the placement is written in chat and in its label above the world, e.g. {@code tower/testrig}. */
	public String label() {
		return build.name() + Build.LABEL_SEPARATOR + name;
	}

	public ResourceKey<Level> dimension() {
		return placement.dimension();
	}

	/** The world position of build space {@code (0, 0, 0)} for this placement, see {@link Placement#origin}. */
	public BlockPos origin() {
		return placement.origin();
	}

	/** The version the placement's box is expected to hold, see {@link Placement#head}. */
	public int head() {
		return placement.head();
	}

	/** The box the placement covers in the world: the extent of the version it holds, laid at its origin. */
	public BuildBox box() {
		return boxOf(head());
	}

	/** The box {@code version} would cover at this placement, which is the current {@link #box} only for the head. */
	public BuildBox boxOf(int version) {
		return build.extent(version).at(origin());
	}

	/** The same placement seen through {@code build}, e.g. after a commit has given the build another version. */
	public BuildPlacement in(Build build) {
		return new BuildPlacement(build, name, build.placements().get(name));
	}

	/** The same placement holding {@code head}, as it is after a checkout or a commit; the build is left alone. */
	public BuildPlacement withHead(int head) {
		return new BuildPlacement(build, name, placement.withHead(head));
	}

	/** The build with this placement's latest state written into it, ready to be saved. */
	public Build applied() {
		return build.withPlacement(name, placement);
	}
}
