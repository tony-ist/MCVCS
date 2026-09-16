package tony.mcvcs.command;

import java.util.List;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs builds}: lists every build in the world, each with a chat button that runs {@code /vcs select} for it,
 * see {@link ChatButtons}; the selected one is marked instead.
 */
public final class VcsCommandBuilds {
	/** Label of the button put after each build that is not selected. */
	public static final String SELECT_BUTTON = "Select";
	/** Marker put after the selected build instead of a button. */
	public static final String SELECTED_MARKER = "selected";

	private VcsCommandBuilds() {
	}

	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<Build> builds = BuildRegistry.all(source.getServer());
		if (builds.isEmpty()) {
			source.sendFailure(Component.literal("No builds in this world; create one with ").append(ChatButtons.template("/vcs create <buildname>")));
			return 0;
		}

		String selected = BuildRegistry.selected(player).map(Build::name).orElse(null);
		source.sendSuccess(() -> Component.literal(builds.size() + (builds.size() == 1 ? " build" : " builds") + " in this world:"), false);
		for (Build build : builds) {
			source.sendSuccess(() -> buildLine(build, build.name().equals(selected)), false);
		}
		return builds.size();
	}

	/**
	 * One line of the listing: the build's name, version and size, followed by a clickable {@code [Select]} that runs
	 * {@code /vcs select} for it, or a {@code [selected]} marker if it already is.
	 */
	private static MutableComponent buildLine(Build build, boolean selected) {
		MutableComponent line = Component.literal("- ").append(VcsMessages.name(build.name())).append(" v" + build.version() + " (" + build.box().volume() + " blocks) ");
		if (selected) {
			return line.append(ComponentUtils.wrapInSquareBrackets(Component.literal(SELECTED_MARKER)).withStyle(ChatFormatting.GRAY));
		}
		return line.append(ChatButtons.button(SELECT_BUTTON, "/vcs select " + build.name()));
	}
}
