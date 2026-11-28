package tony.mcvcs.command;

import java.io.IOException;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.BuildStorage;

/**
 * {@code /vcs label <version> <tagname>}: gives a version of the selected placement's build a tag, such as {@code 2.0.0},
 * recorded in {@code build.json}. A version may carry several tags, but a tag names one version only, so a tag that
 * already names another version is refused rather than moved. {@code /vcs save <tagname>} tags the version it saves
 * the same way, see {@link #refusal}. {@code /vcs unlabel <tagname>} removes a tag again, leaving the version it named.
 */
public final class VcsCommandTag {
	static final VcsHelp HELP = new VcsHelp("label", "/vcs label <version | label> <label>",
		"name a version of the selected build, e.g. with-roof",
		"Gives that version of the selected build a label, e.g. `/vcs label 2 with-roof`. Use letters, digits, - _ + and dots, but not only digits. A version can have several labels, but each label belongs to one version. Commands that take a version number, such as `/vcs load`, take a label too. `/vcs save <label>` labels the version it saves. `/vcs unlabel <label>` removes a label.");
	static final VcsHelp UNTAG_HELP = new VcsHelp("unlabel", "/vcs unlabel <label>",
		"remove a label from the selected build",
		"Removes the label from the selected build, e.g. `/vcs unlabel with-roof`. The version itself stays.");

	private VcsCommandTag() {
	}

	/** @param typed the version to tag, by number or by a tag it already has */
	static int run(CommandSourceStack source, VersionRef typed, String tag) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		Build build = selected.get().build();
		// The command always names a version, so the fallback is never used.
		Optional<Integer> resolved = typed.resolve(source, build, build.version());
		if (resolved.isEmpty()) {
			return 0;
		}
		int version = resolved.get();
		if (build.taggedVersion(tag).filter(tagged -> tagged == version).isPresent()) {
			source.sendFailure(Component.literal("v" + version + " of build ").append(VcsMessages.name(build.name())).append(" is already labeled " + tag));
			return 0;
		}
		Optional<MutableComponent> refusal = refusal(build, tag);
		if (refusal.isPresent()) {
			source.sendFailure(refusal.get());
			return 0;
		}

		Build tagged = build.withTag(tag, version);
		try {
			BuildStorage.update(tagged);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to tag '{}' v{} as '{}' for {}", build.name(), version, tag, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to save label: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} tagged build '{}' v{} as '{}'", player.getGameProfile().name(), build.name(), version, tag);

		source.sendSuccess(() -> Component.literal("Labeled build ").append(VcsMessages.name(build.name())).append(" " + tagged.versionLabel(version)), false);
		return 1;
	}

	/** {@code /vcs unlabel <tagname>}. */
	static int untag(CommandSourceStack source, String tag) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<BuildPlacement> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noPlacementSelected());
			return 0;
		}

		Build build = selected.get().build();
		Optional<Integer> tagged = build.taggedVersion(tag);
		if (tagged.isEmpty()) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" has no label " + tag));
			return 0;
		}
		int version = tagged.get();

		Build untagged = build.withoutTag(tag);
		try {
			BuildStorage.update(untagged);
		} catch (IOException e) {
			MCVCS.LOGGER.error("Failed to remove tag '{}' from '{}' v{} for {}", tag, build.name(), version, player.getGameProfile().name(), e);
			source.sendFailure(Component.literal("Failed to remove label: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} removed tag '{}' from build '{}' v{}", player.getGameProfile().name(), tag, build.name(), version);

		source.sendSuccess(() -> Component.literal("Removed label " + tag + " from build ").append(VcsMessages.name(build.name())).append(" " + untagged.versionLabel(version)), false);
		return 1;
	}

	/**
	 * Why {@code tag} cannot be given to a version of {@code build}: it does not look like a tag, or it already names
	 * one of the build's versions. Empty if it can.
	 */
	static Optional<MutableComponent> refusal(Build build, String tag) {
		if (!Build.isValidTag(tag)) {
			return Optional.of(Component.literal("Label '" + tag + "' is not allowed: use letters, digits, - _ + and dots, but not only digits"));
		}
		return build.taggedVersion(tag).map(tagged -> Component.literal("Label " + tag + " is already on v" + tagged + " of build ")
			.append(VcsMessages.name(build.name())));
	}
}
