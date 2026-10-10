package tony.mcvcs.client.place;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

import tony.mcvcs.MCVCS;
import tony.mcvcs.client.preview.PreviewManager;

/**
 * A line at the bottom middle of the screen, there for as long as a {@code /vcs place} copy is shown, saying that it
 * is only a preview and how to move and place it. Unlike the action bar it does not fade, so it is still there when
 * the player comes back to the copy later.
 */
public final class PlacePreviewHint {
	/** Above the action bar, which is centred 68 pixels up from the bottom, so the two never overlap. */
	private static final int OFFSET_FROM_BOTTOM = 84;

	private PlacePreviewHint() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.OVERLAY_MESSAGE, MCVCS.id("place_preview_hint"), PlacePreviewHint::extract);
	}

	/** E.g. {@code Placement preview. Left Alt + Mouse Wheel to move, /vcs confirmPlace to place}, the command in green. */
	public static Component message() {
		return Component.literal("Placement preview. ")
			.append(PlacePreviewKeys.SCROLL.getTranslatedKeyMessage())
			.append(" + Mouse Wheel to move, ")
			.append(Component.literal("/vcs confirmPlace").withStyle(ChatFormatting.GREEN))
			.append(" to place");
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (PreviewManager.place() == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		Component message = message();
		int width = font.width(message);
		graphics.textWithBackdrop(font, message, (graphics.guiWidth() - width) / 2, graphics.guiHeight() - OFFSET_FROM_BOTTOM, width, ARGB.white(255));
	}
}
