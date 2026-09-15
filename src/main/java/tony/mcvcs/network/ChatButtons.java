package tony.mcvcs.network;

import java.util.Optional;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;

/**
 * Clickable commands in chat: green text that runs a command for the player who clicks it, or puts a command that
 * still needs an argument into their chat box.
 * <p>
 * A {@link ClickEvent.RunCommand} would do for running, but the client asks for confirmation before running any
 * command that needs permissions, which {@code /vcs} and {@code //paste} do, so every press would open a dialog. A
 * {@link ClickEvent.Custom} is sent to the server as is, without the client looking at it, and works with a vanilla
 * client. The server side is ours: {@link tony.mcvcs.mixin.ServerCommonPacketListenerImplMixin} hands the click to
 * {@link #handle}, which runs the command it carries through the dispatcher as the player, so it is subject to the
 * same permission check and gives the same feedback as typing it. Only commands rooted in {@link #ALLOWED_ROOTS}
 * are run, so a client cannot make a click run anything but ours and {@code //paste}.
 * <p>
 * A command with a placeholder, such as {@code /vcs create <buildname>}, cannot be run as is; clicking it uses a
 * vanilla {@link ClickEvent.SuggestCommand} to put everything before the placeholder into the chat box, and the
 * player finishes it.
 */
public final class ChatButtons {
	/** Click action that runs a command; the payload is the command, without its leading slash, as a string tag. */
	public static final Identifier RUN = MCVCS.id("run");
	/** First words of the commands a click may run: ours, and WorldEdit's {@code //paste}, which is registered as {@code /paste}. */
	public static final Set<String> ALLOWED_ROOTS = Set.of("vcs", "/paste");

	private ChatButtons() {
	}

	/** A click event that runs {@code command}, given with its leading slash, for whoever clicks it. */
	public static ClickEvent run(String command) {
		if (!command.startsWith("/") || !ALLOWED_ROOTS.contains(root(command.substring(1)))) {
			throw new IllegalArgumentException("Not a command a chat button may run: " + command);
		}
		return new ClickEvent.Custom(RUN, Optional.of(StringTag.valueOf(command.substring(1))));
	}

	/** {@code command}, given with its leading slash, in green; clicking it runs it. */
	public static MutableComponent command(String command) {
		return clickable(Component.literal(command), command);
	}

	/** A green {@code [label]} that runs {@code command}, given with its leading slash, when clicked. */
	public static MutableComponent button(String label, String command) {
		return clickable(ComponentUtils.wrapInSquareBrackets(Component.literal(label)), command);
	}

	private static MutableComponent clickable(MutableComponent text, String command) {
		return text.withStyle(style -> style.withColor(ChatFormatting.GREEN)
			.withClickEvent(run(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to run " + command))));
	}

	/**
	 * {@code template}, a command with a {@code <placeholder>} in it, in green; clicking it puts the part before the
	 * placeholder into the chat box for the player to finish.
	 */
	public static MutableComponent template(String template) {
		int placeholder = template.indexOf('<');
		if (placeholder < 0) {
			throw new IllegalArgumentException("No placeholder in " + template);
		}
		String start = template.substring(0, placeholder);
		return Component.literal(template).withStyle(style -> style.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.SuggestCommand(start))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to type " + start))));
	}

	/**
	 * Runs the command a click on one of these stands for, as {@code player}. Called on the server thread.
	 *
	 * @return whether the click was one of ours; a click with an unknown id is left to whoever else may want it
	 */
	public static boolean handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
		if (!id.equals(RUN)) {
			return false;
		}
		Optional<String> command = payload.flatMap(Tag::asString).filter(candidate -> ALLOWED_ROOTS.contains(root(candidate)));
		if (command.isEmpty()) {
			MCVCS.LOGGER.warn("{} clicked a {} button with an invalid payload {}", player.getGameProfile().name(), id, payload.orElse(null));
			return true;
		}
		player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command.get());
		return true;
	}

	/** The first word of {@code command}, which has no leading slash. */
	private static String root(String command) {
		int space = command.indexOf(' ');
		return space < 0 ? command : command.substring(0, space);
	}
}
