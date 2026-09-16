package tony.mcvcs.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import tony.mcvcs.build.BuildBox;
import tony.mcvcs.network.ChatButtons;

/** Chat text more than one {@code /vcs} command puts together the same way. */
final class VcsMessages {
	private VcsMessages() {
	}

	/** A build's name as it appears in chat: light blue, so it stands out without quotes around it. */
	static MutableComponent name(String name) {
		return Component.literal(name).withStyle(ChatFormatting.AQUA);
	}

	/** What a command that needs a selected build says when there is none. */
	static MutableComponent noBuildSelected() {
		return Component.literal("No build selected in this world; run ").append(ChatButtons.template("/vcs create <buildname>")).append(" first");
	}

	/** E.g. {@code 3x2x2}. */
	static String size(BuildBox box) {
		return box.sizeX() + "x" + box.sizeY() + "x" + box.sizeZ();
	}
}
