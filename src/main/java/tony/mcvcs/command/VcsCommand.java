package tony.mcvcs.command;

import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildPlacement;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.build.Placement;

/**
 * {@code /vcs} command tree. Only the syntax lives here: each subcommand is parsed and handed to the class that does
 * the work.
 * <ul>
 * <li>{@code /vcs create <buildname> [placementname] [-we]}: {@link VcsCommandCreate}</li>
 * <li>{@code /vcs place <buildname> [version | tag | latest] [placementname] [-f]}, {@code /vcs confirmPlace [-f]} and
 * {@code /vcs cancelPlace}: {@link VcsCommandPlace}</li>
 * <li>{@code /vcs unplace [-k]} and {@code /vcs confirmUnplace}: {@link VcsCommandUnplace}</li>
 * <li>{@code /vcs select [buildname [placementname]]}: {@link VcsCommandSelect}</li>
 * <li>{@code /vcs builds}: {@link VcsCommandBuilds}</li>
 * <li>{@code /vcs deselect}: {@link VcsCommandDeselect}</li>
 * <li>{@code /vcs commit [tagname]}: {@link VcsCommandCommit}</li>
 * <li>{@code /vcs tag <version | tag> <tagname>}: {@link VcsCommandTag}</li>
 * <li>{@code /vcs preview <version | tag | off>}: {@link VcsCommandPreview}</li>
 * <li>{@code /vcs load [version | tag]}: {@link VcsCommandLoad}</li>
 * <li>{@code /vcs diff [version | tag | off]}: {@link VcsCommandDiff}</li>
 * <li>{@code /vcs expand}: {@link VcsCommandExpand}</li>
 * <li>{@code /vcs checkout <version | tag | latest> [-f]}: {@link VcsCommandCheckout}</li>
 * <li>{@code /vcs delete <buildname> [-c]} and {@code /vcs confirmDelete}: {@link VcsCommandDelete}</li>
 * <li>{@code /vcs tp [buildname [placementname]]}: {@link VcsCommandTp}</li>
 * <li>{@code /vcs weselect}: {@link VcsCommandWeselect}</li>
 * <li>{@code /vcs help [command]} and {@code /vcs -h}: {@link VcsCommandHelp}</li>
 * </ul>
 * Every subcommand also takes {@code -h} in place of its arguments, which shows its help instead of running it, see
 * {@link #sub}. Builds and selections are looked up through {@link BuildRegistry}, which only shows those belonging
 * to the world being played; every command that works on blocks acts on the selected {@link Placement}.
 */
public final class VcsCommand {
	/** Vanilla permission required to run the command (gamemasters = op level 2 / cheats). */
	public static final PermissionCheck PERMISSION = Commands.LEVEL_GAMEMASTERS;
	/**
	 * The version a command falls back to when given none: the build's latest one for {@code load}, {@code place} and
	 * {@code checkout}, the one the selected placement holds for {@code diff}, see {@link Placement#head}.
	 */
	static final VersionRef LATEST = VersionRef.DEFAULT;
	/** Flag that makes a command overwrite what is in the way instead of refusing. */
	public static final String FORCE = "-f";

	private VcsCommand() {
	}

	public static void register() {
		VcsCommandDelete.register();
		VcsCommandUnplace.register();
		VcsCommandPlace.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(Commands.literal("vcs")
				.requires(Commands.hasPermission(PERMISSION))
				.then(Commands.literal(VcsHelp.FLAG)
					.executes(context -> VcsCommandHelp.run(context.getSource())))
				.then(sub(VcsCommandHelp.HELP)
					.executes(context -> VcsCommandHelp.run(context.getSource()))
					.then(Commands.argument("command", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(VcsCommandHelp.ALL.stream().map(VcsHelp::name), builder))
						.executes(context -> VcsCommandHelp.run(context.getSource(), StringArgumentType.getString(context, "command")))))
				.then(sub(VcsCommandCreate.HELP)
					.then(Commands.argument("buildname", StringArgumentType.word())
						.executes(context -> create(context, Build.MAIN, false))
						.then(Commands.literal(VcsCommandCreate.SELECTION)
							.executes(context -> create(context, Build.MAIN, true)))
						.then(Commands.argument("placementname", StringArgumentType.word())
							.executes(context -> create(context, StringArgumentType.getString(context, "placementname"), false))
							.then(Commands.literal(VcsCommandCreate.SELECTION)
								.executes(context -> create(context, StringArgumentType.getString(context, "placementname"), true))))))
				.then(sub(VcsCommandPlace.HELP)
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> place(context, LATEST_VERSION, null, false))
						.then(Commands.literal("latest")
							.executes(context -> place(context, LATEST_VERSION, null, false))
							.then(placementOf(LATEST_VERSION)))
						.then(VersionRef.argument(VcsCommand::namedBuild)
							.executes(context -> place(context, ARGUMENT_VERSION, null, false))
							.then(placementOf(ARGUMENT_VERSION)))))
				.then(sub(VcsCommandPlace.CONFIRM_HELP)
					.executes(context -> VcsCommandPlace.confirm(context.getSource(), false))
					.then(Commands.literal(FORCE)
						.executes(context -> VcsCommandPlace.confirm(context.getSource(), true))))
				.then(sub(VcsCommandPlace.CANCEL_HELP)
					.executes(context -> VcsCommandPlace.cancel(context.getSource())))
				.then(sub(VcsCommandUnplace.HELP)
					.executes(context -> VcsCommandUnplace.run(context.getSource(), false))
					.then(Commands.literal(VcsCommandUnplace.KEEP)
						.executes(context -> VcsCommandUnplace.run(context.getSource(), true))))
				.then(sub(VcsCommandUnplace.CONFIRM_HELP)
					.executes(context -> VcsCommandUnplace.confirm(context.getSource())))
				.then(sub(VcsCommandSelect.HELP)
					.executes(context -> VcsCommandSelect.run(context.getSource()))
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> VcsCommandSelect.run(context.getSource(), StringArgumentType.getString(context, "buildname")))
						.then(Commands.argument("placementname", StringArgumentType.word())
							.suggests((context, builder) -> SharedSuggestionProvider.suggest(placements(context.getSource(), StringArgumentType.getString(context, "buildname")), builder))
							.executes(context -> VcsCommandSelect.run(context.getSource(), StringArgumentType.getString(context, "buildname"), StringArgumentType.getString(context, "placementname"))))))
				.then(sub(VcsCommandBuilds.HELP)
					.executes(context -> VcsCommandBuilds.run(context.getSource())))
				.then(sub(VcsCommandDeselect.HELP)
					.executes(context -> VcsCommandDeselect.run(context.getSource())))
				.then(sub(VcsCommandCommit.HELP)
					.executes(context -> VcsCommandCommit.run(context.getSource(), null))
					.then(Commands.argument("tagname", StringArgumentType.word())
						.executes(context -> VcsCommandCommit.run(context.getSource(), StringArgumentType.getString(context, "tagname")))))
				.then(sub(VcsCommandTag.HELP)
					.then(VersionRef.argument(VcsCommand::selectedBuild)
						.then(Commands.argument("tagname", StringArgumentType.word())
							.executes(context -> VcsCommandTag.run(context.getSource(), VersionRef.of(context), StringArgumentType.getString(context, "tagname"))))))
				.then(sub(VcsCommandPreview.HELP)
					.then(Commands.literal("off")
						.executes(context -> VcsCommandPreview.off(context.getSource())))
					.then(VersionRef.argument(VcsCommand::selectedBuild)
						.executes(context -> VcsCommandPreview.run(context.getSource(), VersionRef.of(context)))))
				.then(sub(VcsCommandLoad.HELP)
					.executes(context -> VcsCommandLoad.run(context.getSource(), LATEST))
					.then(VersionRef.argument(VcsCommand::selectedBuild)
						.executes(context -> VcsCommandLoad.run(context.getSource(), VersionRef.of(context)))))
				.then(sub(VcsCommandDiff.HELP)
					.executes(context -> VcsCommandDiff.run(context.getSource(), LATEST))
					.then(Commands.literal("off")
						.executes(context -> VcsCommandDiff.off(context.getSource())))
					.then(VersionRef.argument(VcsCommand::selectedBuild)
						.executes(context -> VcsCommandDiff.run(context.getSource(), VersionRef.of(context)))))
				.then(sub(VcsCommandExpand.HELP)
					.executes(context -> VcsCommandExpand.run(context.getSource())))
				.then(sub(VcsCommandCheckout.HELP)
					.then(Commands.literal("latest")
						.executes(context -> VcsCommandCheckout.run(context.getSource(), LATEST, false))
						.then(Commands.literal(FORCE)
							.executes(context -> VcsCommandCheckout.run(context.getSource(), LATEST, true))))
					.then(VersionRef.argument(VcsCommand::selectedBuild)
						.executes(context -> VcsCommandCheckout.run(context.getSource(), VersionRef.of(context), false))
						.then(Commands.literal(FORCE)
							.executes(context -> VcsCommandCheckout.run(context.getSource(), VersionRef.of(context), true)))))
				.then(sub(VcsCommandDelete.HELP)
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> VcsCommandDelete.run(context.getSource(), StringArgumentType.getString(context, "buildname"), false))
						.then(Commands.literal(VcsCommandDelete.CLEAR)
							.executes(context -> VcsCommandDelete.run(context.getSource(), StringArgumentType.getString(context, "buildname"), true)))))
				.then(sub(VcsCommandDelete.CONFIRM_HELP)
					.executes(context -> VcsCommandDelete.confirm(context.getSource())))
				.then(sub(VcsCommandTp.HELP)
					.executes(context -> VcsCommandTp.runSelected(context.getSource()))
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> VcsCommandTp.run(context.getSource(), StringArgumentType.getString(context, "buildname")))
						.then(Commands.argument("placementname", StringArgumentType.word())
							.suggests((context, builder) -> SharedSuggestionProvider.suggest(placements(context.getSource(), StringArgumentType.getString(context, "buildname")), builder))
							.executes(context -> VcsCommandTp.run(context.getSource(), StringArgumentType.getString(context, "buildname"), StringArgumentType.getString(context, "placementname"))))))
				.then(sub(VcsCommandWeselect.HELP)
					.executes(context -> VcsCommandWeselect.run(context.getSource())))));
	}

	/**
	 * The literal of the subcommand {@code help} describes, with {@code -h} under it showing that help. Brigadier
	 * tries literals before arguments, so {@code -h} wins over a {@code <buildname>} argument in the same place; a build
	 * cannot be called {@code -h}, which nobody will miss.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> sub(VcsHelp help) {
		return Commands.literal(help.name())
			.then(Commands.literal(VcsHelp.FLAG)
				.executes(context -> {
					help.send(context.getSource());
					return 1;
				}));
	}

	private static int create(CommandContext<CommandSourceStack> context, String placementName, boolean useSelection) throws CommandSyntaxException {
		return VcsCommandCreate.run(context.getSource(), StringArgumentType.getString(context, "buildname"), placementName, useSelection);
	}

	/** Where {@code /vcs place} takes the version from, since the same tail hangs under {@code latest} and a version. */
	@FunctionalInterface
	private interface VersionSource {
		VersionRef of(CommandContext<CommandSourceStack> context);
	}

	/** {@code /vcs place <buildname>} and {@code /vcs place <buildname> latest}: the build's latest version. */
	private static final VersionSource LATEST_VERSION = context -> LATEST;
	/** {@code /vcs place <buildname> <version>}: the number or tag that was typed. */
	private static final VersionSource ARGUMENT_VERSION = VersionRef::of;

	/** The {@code <placementname> [-f]} tail of {@code /vcs place}, under the version that was given. */
	private static RequiredArgumentBuilder<CommandSourceStack, String> placementOf(VersionSource version) {
		return Commands.argument("placementname", StringArgumentType.word())
			.executes(context -> place(context, version, StringArgumentType.getString(context, "placementname"), false))
			.then(Commands.literal(FORCE)
				.executes(context -> place(context, version, StringArgumentType.getString(context, "placementname"), true)));
	}

	private static int place(CommandContext<CommandSourceStack> context, VersionSource version, String placementName, boolean force) throws CommandSyntaxException {
		return VcsCommandPlace.run(context.getSource(), StringArgumentType.getString(context, "buildname"), version.of(context), placementName, force);
	}

	/** The build of the placement the source player has selected; empty if there is no player or selection. */
	private static Optional<Build> selectedBuild(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return Optional.empty();
		}
		return BuildRegistry.selected(player).map(BuildPlacement::build);
	}

	/** The build named by the {@code buildname} argument of the command being typed, if there is one. */
	private static Optional<Build> namedBuild(CommandContext<CommandSourceStack> context) {
		return BuildRegistry.find(context.getSource().getServer(), StringArgumentType.getString(context, "buildname"));
	}

	/** Every placement name of the build called {@code buildName}, for completing a {@code placementname} argument. */
	private static List<String> placements(CommandSourceStack source, String buildName) {
		return BuildRegistry.find(source.getServer(), buildName).map(Build::placementNames).orElse(List.of());
	}
}
