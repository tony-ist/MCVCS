package tony.mcvcs.command;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.Build;

/**
 * A version as a command was given it: a number such as {@code 2}, a tag such as {@code 2.0.0}, see {@link Build#tags},
 * or nothing at all, which each command reads as its own default. Only the command knows which build it acts on, so
 * the version is {@link #resolve resolved} there, once that build has been found.
 *
 * @param number the version number typed, or {@code null} if a tag or nothing was
 * @param tag    the tag typed, or {@code null} if a number or nothing was
 */
record VersionRef(@Nullable Integer number, @Nullable String tag) {
	/** No version given: the command falls back to its default, e.g. the build's latest version. */
	static final VersionRef DEFAULT = new VersionRef(null, null);

	/**
	 * What was typed as a version: a number if it is only digits, which is why a tag cannot be, see
	 * {@link Build#isValidTag}, and a tag otherwise.
	 */
	static VersionRef parse(String typed) {
		if (!typed.isEmpty() && typed.chars().allMatch(c -> c >= '0' && c <= '9')) {
			try {
				return new VersionRef(Integer.parseInt(typed), null);
			} catch (NumberFormatException e) {
				// More digits than any build has versions, so it names none of them.
				return new VersionRef(Integer.MAX_VALUE, null);
			}
		}
		return new VersionRef(null, typed);
	}

	/**
	 * A {@code version} argument taking a number or a tag, completed with every version number and tag of the build
	 * {@code build} finds for the command being typed.
	 */
	static RequiredArgumentBuilder<CommandSourceStack, String> argument(BuildLookup build) {
		return Commands.argument("version", StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(build.of(context)
				.map(found -> Stream.concat(found.versionNumbers().stream().map(String::valueOf), found.tags().keySet().stream()).toList())
				.orElse(List.of()), builder));
	}

	/** The {@code version} argument {@link #argument} added, as typed. */
	static VersionRef of(CommandContext<CommandSourceStack> context) {
		return parse(StringArgumentType.getString(context, "version"));
	}

	/** Finds the build whose versions a {@code version} argument completes, if there is one while it is being typed. */
	@FunctionalInterface
	interface BuildLookup {
		Optional<Build> of(CommandContext<CommandSourceStack> context);
	}

	/**
	 * The version of {@code build} this names, or {@code fallback} if it is {@link #DEFAULT}; empty, having told
	 * {@code source} why, if the build has no such version or no version with that tag.
	 */
	Optional<Integer> resolve(CommandSourceStack source, Build build, int fallback) {
		if (tag != null) {
			Optional<Integer> tagged = build.taggedVersion(tag);
			if (tagged.isEmpty()) {
				source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" has no version tagged " + tag));
			}
			return tagged;
		}
		if (number == null) {
			return Optional.of(fallback);
		}
		if (!build.hasVersion(number)) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" only has versions 1 to " + build.version()));
			return Optional.empty();
		}
		return Optional.of(number);
	}
}
