package tony.mcvcs.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.network.ChatButtons;

/** Chat text more than one {@code /vcs} command puts together the same way. */
final class VcsMessages {
	private VcsMessages() {
	}

	/** A build's name as it appears in chat: light blue, so it stands out without quotes around it. */
	static MutableComponent name(String name) {
		return Component.literal(name).withStyle(ChatFormatting.AQUA);
	}

	/** A placement as it appears in chat: its build and its own name, light blue, e.g. {@code tower/testrig}. */
	static MutableComponent placement(BuildPlacement placement) {
		return name(placement.label());
	}

	/** What a command that needs a selected placement says when there is none. */
	static MutableComponent noPlacementSelected() {
		return Component.literal("Nothing selected; run ").append(ChatButtons.command("/vcs select"))
			.append(" and hit a block of a build, or ").append(ChatButtons.template("/vcs create <buildname>")).append(" to start one");
	}

	/** What a command says when {@code placement} is in a dimension this server does not have. */
	static MutableComponent missingDimension(BuildPlacement placement) {
		return Component.empty().append(placement(placement)).append(" is in " + placement.dimension().identifier() + ", which does not exist here");
	}

	/** What a command says when the file of version {@code version} of {@code build} is missing. */
	static MutableComponent missingVersion(String build, int version) {
		return Component.literal("Can't find the saved file of ").append(name(build)).append(" v" + version);
	}

	/** E.g. {@code 3 unsaved changes}. */
	static String unsavedChanges(int count) {
		return count + (count == 1 ? " unsaved change" : " unsaved changes");
	}

	/** What a command says when two copies would overlap. */
	static final String NO_OVERLAP = "; copies can't overlap";

	/** E.g. {@code 3x2x2}. */
	static String size(BuildBox box) {
		return box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ();
	}

	/**
	 * A line of yellow {@code =} with {@code title} in the middle, as wide as the chat, the way WorldEdit heads its
	 * {@code //help} box. The server cannot measure text, so the width comes from {@link #textWidth}.
	 */
	static MutableComponent header(String title) {
		String middle = " " + title + " ";
		int sideWidth = Math.max(0, (CHAT_WIDTH - textWidth(middle)) / 2);
		String side = "=".repeat(sideWidth / textWidth("="));
		return Component.literal(side).withStyle(ChatFormatting.YELLOW)
			.append(Component.literal(middle).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(side).withStyle(ChatFormatting.YELLOW));
	}

	/** Width of the chat, in the pixels of the default font at GUI scale 1, on a client that has not changed it. */
	private static final int CHAT_WIDTH = 320;

	/**
	 * How wide {@code text} is in the default font, in pixels including the one after each glyph. Most glyphs take 6;
	 * the narrow ones are listed here, and anything not in the default font is taken to be 6 as well.
	 */
	private static int textWidth(String text) {
		int width = 0;
		for (char c : text.toCharArray()) {
			width += switch (c) {
				case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
				case 'l', '`' -> 3;
				case ' ', 'I', 't', '[', ']' -> 4;
				case '(', ')', '{', '}', '"' -> 5;
				default -> 6;
			};
		}
		return width;
	}
}
