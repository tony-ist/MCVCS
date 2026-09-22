package tony.mcvcs.client.preview;

import net.minecraft.core.BlockPos;

import tony.mcvcs.build.BuildBox;

/**
 * The copy a {@code /vcs place} is about to put down, drawn where it would land and moved about by the player
 * before {@code /vcs confirmPlace} makes it real.
 * <p>
 * The blocks never change while it is being aligned, only where they are drawn, so moving it is a matter of laying
 * the same {@link ClientPreview#blocks} over another box. Immutable, like the preview it holds, so the section
 * compile threads can read it without locking.
 *
 * @param blocks what is drawn, and where it is drawn right now
 * @param force  whether the {@code /vcs place} was given {@code -f}, so blocks standing in the way do not refuse it
 */
public record PlacePreview(ClientPreview blocks, boolean force) {
	/** Where the copy stands right now. */
	public BuildBox box() {
		return blocks.box();
	}

	/** The same copy drawn with its minimum corner at {@code min} instead. */
	public PlacePreview movedTo(BlockPos min) {
		BuildBox box = blocks.box();
		BuildBox moved = new BuildBox(min, min.offset(box.sizeX() - 1, box.sizeY() - 1, box.sizeZ() - 1));
		return new PlacePreview(new ClientPreview(blocks.name(), blocks.version(), blocks.dimension(), moved, blocks.blocks()), force);
	}
}
