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
		return Component.literal("Nothing selected in this world; run ").append(ChatButtons.command("/vcs select"))
			.append(" and punch a block of a placement, or ").append(ChatButtons.template("/vcs create <buildname>")).append(" to start a build");
	}

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
