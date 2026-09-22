package tony.mcvcs.build;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.Level;

/**
 * One copy of a build standing in the world. A build may have several, each living its own life: each holds a
 * version of its own, can be modified and checked out on its own, and a commit from any of them saves the build's
 * next version, which every other placement can then check out.
 * <p>
 * A placement is a frame, not a box: {@link #origin} nails build space {@code (0, 0, 0)} to a world position once,
 * when the placement is made, and nothing moves it afterwards. The box the placement covers is the extent of the
 * version it holds, see {@link Build#extent}, laid at that origin, so checking out a version of another size grows
 * or shrinks the box around the build's own blocks instead of sliding the build sideways.
 *
 * @param dimension the dimension the placement stands in; placements of one build may be in different ones
 * @param origin    the world position of build space {@code (0, 0, 0)}, fixed for the placement's whole life
 * @param head      the version the placement is expected to hold: what it was placed at, committed as or checked
 *                  out to. What its box holds beyond that is uncommitted work.
 */
public record Placement(ResourceKey<Level> dimension, BlockPos origin, int head) {
	public static final Codec<Placement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Placement::dimension),
		BlockPos.CODEC.fieldOf("origin").forGetter(Placement::origin),
		ExtraCodecs.POSITIVE_INT.fieldOf("head").forGetter(Placement::head)
	).apply(instance, Placement::new));

	/** The same placement holding {@code head} instead, as it is after a checkout or a commit. */
	public Placement withHead(int head) {
		return new Placement(dimension, origin, head);
	}
}
