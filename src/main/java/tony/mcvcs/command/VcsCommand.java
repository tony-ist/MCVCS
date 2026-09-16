package tony.mcvcs.command;

import java.util.List;
import java.util.stream.IntStream;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;

/**
 * {@code /vcs} command tree. Only the syntax lives here: each subcommand is parsed and handed to the class that does
 * the work.
 * <ul>
 * <li>{@code /vcs create <buildname>}: {@link VcsCommandCreate}</li>
 * <li>{@code /vcs select <buildname>}: {@link VcsCommandSelect}</li>
 * <li>{@code /vcs builds}: {@link VcsCommandBuilds}</li>
 * <li>{@code /vcs deselect}: {@link VcsCommandDeselect}</li>
 * <li>{@code /vcs commit}: {@link VcsCommandCommit}</li>
 * <li>{@code /vcs preview <version | off>}: {@link VcsCommandPreview}</li>
 * <li>{@code /vcs load [version]}: {@link VcsCommandLoad}</li>
 * <li>{@code /vcs diff [version | off]}: {@link VcsCommandDiff}</li>
 * <li>{@code /vcs expand}: {@link VcsCommandExpand}</li>
 * <li>{@code /vcs checkout <version | latest> [-f]}: {@link VcsCommandCheckout}</li>
 * <li>{@code /vcs delete <buildname>} and {@code /vcs confirmDelete}: {@link VcsCommandDelete}</li>
 * <li>{@code /vcs tp [buildname]}: {@link VcsCommandTp}</li>
 * <li>{@code /vcs help [command]} and {@code /vcs -h}: {@link VcsCommandHelp}</li>
 * </ul>
 * Every subcommand also takes {@code -h} in place of its arguments, which shows its help instead of running it, see
 * {@link #sub}. Builds and selections are looked up through {@link BuildRegistry}, which only shows those belonging
 * to the world being played; a build remembers which world and dimension its box is in.
 */
public final class VcsCommand {
	/** Vanilla permission required to run the command (gamemasters = op level 2 / cheats). */
	public static final PermissionCheck PERMISSION = Commands.LEVEL_GAMEMASTERS;
	/**
	 * Version number standing for the version {@code /vcs load} or {@code /vcs diff} falls back to when given none, and
	 * {@code /vcs checkout latest} asks for: the selected build's latest one for {@code load} and {@code checkout}, the
	 * one its box holds for {@code diff}, see {@link Build#head}.
	 */
	static final int LATEST = 0;
	/** Flag after {@code /vcs checkout <version>} that makes it check out over uncommitted changes instead of refusing. */
	public static final String FORCE = "-f";

	private VcsCommand() {
	}

	public static void register() {
		VcsCommandDelete.register();
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
						.executes(context -> VcsCommandCreate.run(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(sub(VcsCommandSelect.HELP)
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> VcsCommandSelect.run(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(sub(VcsCommandBuilds.HELP)
					.executes(context -> VcsCommandBuilds.run(context.getSource())))
				.then(sub(VcsCommandDeselect.HELP)
					.executes(context -> VcsCommandDeselect.run(context.getSource())))
				.then(sub(VcsCommandCommit.HELP)
					.executes(context -> VcsCommandCommit.run(context.getSource())))
				.then(sub(VcsCommandPreview.HELP)
					.then(Commands.literal("off")
						.executes(context -> VcsCommandPreview.off(context.getSource())))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> VcsCommandPreview.run(context.getSource(), IntegerArgumentType.getInteger(context, "version")))))
				.then(sub(VcsCommandLoad.HELP)
					.executes(context -> VcsCommandLoad.run(context.getSource(), LATEST))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> VcsCommandLoad.run(context.getSource(), IntegerArgumentType.getInteger(context, "version")))))
				.then(sub(VcsCommandDiff.HELP)
					.executes(context -> VcsCommandDiff.run(context.getSource(), LATEST))
					.then(Commands.literal("off")
						.executes(context -> VcsCommandDiff.off(context.getSource())))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> VcsCommandDiff.run(context.getSource(), IntegerArgumentType.getInteger(context, "version")))))
				.then(sub(VcsCommandExpand.HELP)
					.executes(context -> VcsCommandExpand.run(context.getSource())))
				.then(sub(VcsCommandCheckout.HELP)
					.then(Commands.literal("latest")
						.executes(context -> VcsCommandCheckout.run(context.getSource(), LATEST, false))
						.then(Commands.literal(FORCE)
							.executes(context -> VcsCommandCheckout.run(context.getSource(), LATEST, true))))
					.then(Commands.argument("version", IntegerArgumentType.integer(1))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(versions(context.getSource()), builder))
						.executes(context -> VcsCommandCheckout.run(context.getSource(), IntegerArgumentType.getInteger(context, "version"), false))
						.then(Commands.literal(FORCE)
							.executes(context -> VcsCommandCheckout.run(context.getSource(), IntegerArgumentType.getInteger(context, "version"), true)))))
				.then(sub(VcsCommandDelete.HELP)
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> VcsCommandDelete.run(context.getSource(), StringArgumentType.getString(context, "buildname")))))
				.then(sub(VcsCommandDelete.CONFIRM_HELP)
					.executes(context -> VcsCommandDelete.confirm(context.getSource())))
				.then(sub(VcsCommandTp.HELP)
					.executes(context -> VcsCommandTp.runSelected(context.getSource()))
					.then(Commands.argument("buildname", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(BuildRegistry.names(context.getSource().getServer()), builder))
						.executes(context -> VcsCommandTp.run(context.getSource(), StringArgumentType.getString(context, "buildname")))))));
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

	/** Every version number of the build the source player has selected; nothing if there is no player or selection. */
	private static List<String> versions(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return List.of();
		}
		return BuildRegistry.selected(player)
			.map(build -> IntStream.rangeClosed(1, build.version()).mapToObj(Integer::toString).toList())
			.orElse(List.of());
	}
}
