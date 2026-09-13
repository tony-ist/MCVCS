package tony.mcvcs.client.diff;

import java.util.List;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import tony.mcvcs.diff.BuildDiff;

/**
 * A fully received diff: which build and version the world was compared against, the diff itself and the boxes
 * that highlight it, merged once on receipt, see {@link DiffHighlights}.
 * <p>
 * Immutable, so the render thread can read it without locking.
 */
public record ClientDiff(String name, int version, ResourceKey<Level> dimension, BuildDiff diff, List<DiffHighlights.Highlight> highlights) {
	public ClientDiff(String name, int version, ResourceKey<Level> dimension, BuildDiff diff) {
		this(name, version, dimension, diff, DiffHighlights.merge(diff));
	}
}
