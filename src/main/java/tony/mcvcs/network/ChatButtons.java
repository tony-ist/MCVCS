package tony.mcvcs.network;

import java.util.Optional;

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;

/**
 * Chat buttons that run a {@code /vcs} command for the player who clicks them.
 * <p>
 * A {@link ClickEvent.RunCommand} would do, but the client asks for confirmation before running any command that
 * needs permissions, which {@code /vcs} does, so every press would open a dialog. A {@link ClickEvent.Custom} is
 * sent to the server as is, without the client looking at it, and works with a vanilla client. The server side is
 * ours: {@link tony.mcvcs.mixin.ServerCommonPacketListenerImplMixin} hands the click to {@link #handle}, which turns
 * it back into the command and runs it through the dispatcher as the player, so it is subject to the same
 * permission check and gives the same feedback as typing it. The payload only carries the build name, so a client
 * cannot make a button run anything but {@code /vcs select}.
 */
public final class ChatButtons {
	/** Click action of the {@code [Select]} buttons; the payload is the build name as a string tag. */
	public static final Identifier SELECT = MCVCS.id("select");

	private ChatButtons() {
	}

	/** A click event that runs {@code /vcs select <name>} for whoever clicks it. */
	public static ClickEvent select(String name) {
		return new ClickEvent.Custom(SELECT, Optional.of(StringTag.valueOf(name)));
	}

	/**
	 * Runs the command a click on one of these buttons stands for, as {@code player}. Called on the server thread.
	 *
	 * @return whether the click was one of ours; a click with an unknown id is left to whoever else may want it
	 */
	public static boolean handle(ServerPlayer player, Identifier id, Optional<Tag> payload) {
		if (!id.equals(SELECT)) {
			return false;
		}
		Optional<String> name = payload.flatMap(Tag::asString).filter(Build::isValidName);
		if (name.isEmpty()) {
			MCVCS.LOGGER.warn("{} clicked a {} button with an invalid payload {}", player.getGameProfile().name(), id, payload.orElse(null));
			return true;
		}
		player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "vcs select " + name.get());
		return true;
	}
}
