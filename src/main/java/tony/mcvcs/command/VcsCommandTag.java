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
 * {@code /vcs tag <version> <tagname>}: gives a version of the selected placement's build a tag, such as {@code 2.0.0},
 * recorded in {@code build.json}. A version may carry several tags, but a tag names one version only, so a tag that
 * already names another version is refused rather than moved. {@code /vcs commit <tagname>} tags the version it saves
 * the same way, see {@link #refusal}.
 */
public final class VcsCommandTag {
	static final VcsHelp HELP = new VcsHelp("tag", "/vcs tag <version | tag> <tagname>",
		"tag a version of the selected placement's build, e.g. 2.0.0",
		"Gives that version of the selected placement's build a tag, e.g. /vcs tag 2 2.0.0. A tag may contain letters, digits, -, _, + and dots, but may not be digits alone. A version can have several tags, but each tag names one version of a build, so a tag already in use is refused. Wherever a command takes a version number, such as /vcs checkout, /vcs diff or /vcs preview, it takes a tag too. /vcs commit <tagname> tags the version it saves in the same way.");

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
			source.sendFailure(Component.literal("v" + version + " of build ").append(VcsMessages.name(build.name())).append(" is already tagged " + tag));
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
			source.sendFailure(Component.literal("Failed to save tag: " + e.getMessage()));
			return 0;
		}
		MCVCS.LOGGER.info("{} tagged build '{}' v{} as '{}'", player.getGameProfile().name(), build.name(), version, tag);

		source.sendSuccess(() -> Component.literal("Tagged build ").append(VcsMessages.name(build.name())).append(" " + tagged.versionLabel(version)), false);
		return 1;
	}

	/**
	 * Why {@code tag} cannot be given to a version of {@code build}: it does not look like a tag, or it already names
	 * one of the build's versions. Empty if it can.
	 */
	static Optional<MutableComponent> refusal(Build build, String tag) {
		if (!Build.isValidTag(tag)) {
			return Optional.of(Component.literal("Invalid tag '" + tag + "': a tag may only contain letters, digits, -, _, + and dots, and may not be digits alone, which would read as a version number"));
		}
		return build.taggedVersion(tag).map(tagged -> Component.literal("Tag " + tag + " already names v" + tagged + " of build ")
			.append(VcsMessages.name(build.name())));
	}
}
