package tony.mcvcs.client.browser;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.command.VcsCommandDelete;
import tony.mcvcs.network.BuildSummary;

/**
 * Asks whether to delete a build, for the {@code Delete} item of a cell's menu in the builds overlay: a window over the
 * overlay asking "Are you sure you want to delete <build>?", with a checkbox, unticked to begin with, to also empty the
 * boxes of all its placements. {@code Delete} runs {@code /vcs delete} for the build, with {@code -c} if the box is
 * ticked, then {@code /vcs confirmDelete}, as if typed, and goes back to the overlay, whose list is asked for again
 * once the build is gone.
 * {@code Cancel}, Escape or a click outside the window goes back to the overlay; the overlay's key closes both.
 */
public final class DeleteBuildScreen extends Screen {
	/** Labels of the window's buttons and checkbox. */
	public static final String DELETE = "Delete";
	public static final String CANCEL = "Cancel";
	public static final String CLEAR = "Also delete all placed blocks";

	/** How wide the window is, unless the screen is narrower. */
	private static final int WIDTH = 240;
	/** Space between the window's edges and what is inside it, and between the things inside it. */
	private static final int PADDING = 8;
	private static final int BUTTON_WIDTH = 80;
	private static final int BUTTON_HEIGHT = 20;
	/** Space kept between the window and the edges of the screen. */
	private static final int MARGIN = 16;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	/** Laid over the overlay, so the window stands out from it. */
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int BORDER = 0xFFB0B0B0;

	private final BuildBrowserScreen parent;
	private final BuildSummary build;
	private boolean clear;

	public DeleteBuildScreen(BuildBrowserScreen parent, BuildSummary build) {
		super(Component.literal("Are you sure you want to delete " + build.name() + "?"));
		this.parent = parent;
		this.build = build;
	}

	@Override
	protected void init() {
		// The overlay is drawn behind the window, so it has to be laid out for the screen as it is now.
		parent.resize(width, height);
		Layout layout = layout();
		Checkbox checkbox = Checkbox.builder(Component.literal(CLEAR), font)
			.pos(layout.left() + PADDING, layout.checkboxTop())
			.maxWidth(layout.width() - 2 * PADDING)
			.selected(clear)
			.onValueChange((box, value) -> clear = value)
			.build();
		checkbox.setTooltip(Tooltip.create(Component.literal(build.placements() == 0
			? "It is not placed anywhere, so there are no blocks to delete"
			: "Empties the " + (build.placements() == 1 ? "box of its placement" : "boxes of its " + build.placements() + " placements")
				+ "; without this, their blocks stay in the world")));
		addRenderableWidget(checkbox);

		int buttonsLeft = width / 2 - BUTTON_WIDTH - PADDING / 2;
		addRenderableWidget(Button.builder(Component.literal(DELETE).withStyle(ChatFormatting.RED), button -> delete())
			.bounds(buttonsLeft, layout.buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(buttonsLeft + BUTTON_WIDTH + PADDING, layout.buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// The overlay as it was, with the mouse off it so none of its cells is hovered, and dimmed.
		parent.extractBackground(graphics, -1, -1, partialTick);
		graphics.nextStratum();
		parent.extractRenderState(graphics, -1, -1, partialTick);
		graphics.nextStratum();
		graphics.fill(0, 0, width, height, DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Layout layout = layout();
		graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), PANEL);
		graphics.outline(layout.left() - 1, layout.top() - 1, layout.width() + 2, layout.height() + 2, BORDER);
		int y = layout.top() + PADDING;
		for (FormattedCharSequence line : question(layout)) {
			graphics.centeredText(font, line, width / 2, y, WHITE);
			y += font.lineHeight;
		}
		y += PADDING / 2;
		for (FormattedCharSequence line : warning(layout)) {
			graphics.centeredText(font, line, width / 2, y, GREY);
			y += font.lineHeight;
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** The question, wrapped to the window. */
	private List<FormattedCharSequence> question(Layout layout) {
		return font.split(title, layout.width() - 2 * PADDING);
	}

	/** What deleting loses, wrapped to the window. */
	private List<FormattedCharSequence> warning(Layout layout) {
		String versions = build.version() == 1 ? "Its only version" : "All " + build.version() + " of its versions";
		return font.split(Component.literal(versions + " will be lost. This cannot be undone."), layout.width() - 2 * PADDING);
	}

	/**
	 * Goes back to the overlay and runs the two commands that delete the build, as if typed, so the server checks the
	 * permission and answers in chat the way it does for them. The list is asked for again once the deletion is done.
	 */
	private void delete() {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		onClose();
		if (player != null) {
			player.connection.sendCommand("vcs delete " + build.name() + (clear ? " " + VcsCommandDelete.CLEAR : ""));
			player.connection.sendCommand("vcs confirmDelete");
			BuildBrowser.refreshAfterCommand();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		Layout layout = layout();
		if (event.button() == 0 && (event.x() < layout.left() || event.x() >= layout.right() || event.y() < layout.top() || event.y() >= layout.bottom())) {
			onClose();
			return true;
		}
		return false;
	}

	/** Back to the overlay, which is still as it was. */
	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BuildBrowserKey.KEY.matches(event) && minecraft != null) {
			minecraft.setScreen(null);
			return true;
		}
		return super.keyPressed(event);
	}

	/** The button or checkbox labelled {@code label} as it is laid out now, for tests to click; null if there is none. */
	public @Nullable ScreenRectangle widgetOf(String label) {
		return children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().equals(label))
			.map(child -> ((AbstractWidget) child).getRectangle())
			.findFirst().orElse(null);
	}

	/** The window as tall as its text, checkbox and buttons need, centred on the screen. */
	private Layout layout() {
		int panelWidth = Math.min(WIDTH, width - 2 * MARGIN);
		Layout sized = new Layout(0, 0, panelWidth, 0, 0, 0);
		int text = (question(sized).size() + warning(sized).size()) * font.lineHeight + PADDING / 2;
		int checkbox = Checkbox.getBoxSize(font);
		int panelHeight = PADDING + text + PADDING + checkbox + PADDING + BUTTON_HEIGHT + PADDING;
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		int checkboxTop = top + PADDING + text + PADDING;
		return new Layout(left, top, panelWidth, panelHeight, checkboxTop, checkboxTop + checkbox + PADDING);
	}

	/**
	 * Where the window is: {@code width} by {@code height} pixels with its top left corner at {@code (left, top)}, and
	 * where its checkbox and buttons start.
	 */
	private record Layout(int left, int top, int width, int height, int checkboxTop, int buttonsTop) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}
	}
}
