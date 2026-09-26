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
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs builds}: lists every build in the world with its placements, each with a chat button that runs
 * {@code /vcs select} for it, see {@link ChatButtons}, the selected placement being marked instead, and one that runs
 * {@code /vcs tp} to it.
 */
public final class VcsCommandBuilds {
	/** Label of the button put after each placement that is not selected. */
	public static final String SELECT_BUTTON = "Select";
	/** Marker put after the selected placement instead of a button. */
	public static final String SELECTED_MARKER = "selected";
	/** Label of the button put after every placement, selected or not, that teleports onto it. */
	public static final String TP_BUTTON = "Tp";

	static final VcsHelp HELP = new VcsHelp("builds", "/vcs builds",
		"list the builds in this world",
		"Lists every build in this world with its latest version, then each of its placements with the version it holds and its size. Each placement has a [Select] button that selects it, or a [selected] marker if it already is, and a [Tp] button that teleports you onto it.");

	private VcsCommandBuilds() {
	}

	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<Build> builds = BuildRegistry.all(source.getServer());
		if (builds.isEmpty()) {
			source.sendFailure(Component.literal("No builds in this world; create one with ").append(ChatButtons.template("/vcs create <buildname>")));
			return 0;
		}

		String selected = BuildRegistry.selected(player).map(BuildPlacement::label).orElse(null);
		source.sendSuccess(() -> Component.literal(builds.size() + (builds.size() == 1 ? " build" : " builds") + " in this world:"), false);
		int placements = 0;
		for (Build build : builds) {
			source.sendSuccess(() -> buildLine(build), false);
			for (BuildPlacement placement : BuildPlacement.allOf(build)) {
				source.sendSuccess(() -> placementLine(placement, placement.label().equals(selected)), false);
				placements++;
			}
		}
		return placements;
	}

	/** The heading of one build: its name and how many versions it has, with the latest one's tags. */
	private static MutableComponent buildLine(Build build) {
		return Component.literal("- ").append(VcsMessages.name(build.name())).append(" " + build.versionLabel(build.version())
			+ (build.placements().isEmpty() ? ", not placed anywhere" : ""));
	}

	/**
	 * One line under a build: the placement's name, labelled as one, the version it holds and its size, followed by a clickable
	 * {@code [Select]} that runs {@code /vcs select} for it, or a {@code [selected]} marker if it already is, and then a
	 * clickable {@code [Tp]} that runs {@code /vcs tp} to it.
	 */
	private static MutableComponent placementLine(BuildPlacement placement, boolean selected) {
		MutableComponent line = Component.literal("    Placement ").append(VcsMessages.name(placement.name()))
			.append(" " + placement.build().versionLabel(placement.head()) + " (" + placement.box().volume() + " blocks) at " + placement.box().min().toShortString() + " ");
		String target = placement.build().name() + " " + placement.name();
		line.append(selected
			? ComponentUtils.wrapInSquareBrackets(Component.literal(SELECTED_MARKER)).withStyle(ChatFormatting.GRAY)
			: ChatButtons.button(SELECT_BUTTON, "/vcs select " + target));
		return line.append(" ").append(ChatButtons.button(TP_BUTTON, "/vcs tp " + target));
	}
}
