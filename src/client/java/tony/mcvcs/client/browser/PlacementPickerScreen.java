package tony.mcvcs.client.browser;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;

/**
 * Asks which placement the builds overlay's {@code Select} or {@code TP} button means, for a build that has several: a
 * window over the overlay, headed with the build's name, listing each placement's name and the corner its box starts
 * at, and its dimension if that is not the overworld. Clicking one closes the window and the overlay and runs
 * {@code /vcs select} or {@code /vcs tp} for that placement, as if typed. {@code Cancel}, Escape or a click outside the
 * window goes back to the overlay; the overlay's key closes both.
 * <p>
 * The list is the placements the client knew of when the window opened. The selected placement's name is yellow, the
 * colour its box is drawn in.
 */
public final class PlacementPickerScreen extends Screen {
	/** What picking a placement does. */
	public enum Action {
		SELECT("Select a placement of ", "vcs select"),
		TP("Teleport to a placement of ", "vcs tp");

		private final String header;
		private final String command;

		Action(String header, String command) {
			this.header = header;
			this.command = command;
		}

		/** The command it runs, without the slash, the build or the placement, e.g. {@code vcs tp}. */
		public String command() {
			return command;
		}
	}

	/** Height of each placement's row. */
	private static final int ROW = 20;
	/** Space between the window's edges and what is inside it. */
	private static final int PADDING = 8;
	/** Height of the strip at the top of the window holding its header. */
	private static final int HEADER = 24;
	/** Height of the strip at the bottom of the window holding the cancel button. */
	private static final int FOOTER = 32;
	/** Space kept between the window and the edges of the screen. */
	private static final int MARGIN = 16;
	/** The narrowest the window gets, however short its header and rows. */
	private static final int MIN_WIDTH = 160;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int YELLOW = 0xFFFFFF55;
	/** Laid over the overlay, so the window stands out from it. */
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int BORDER = 0xFFB0B0B0;
	private static final int ROW_HOVERED = 0xFF304A6A;

	private final BuildBrowserScreen parent;
	private final String build;
	private final Action action;
	private final List<ClientPlacement> placements;
	private double scroll;

	public PlacementPickerScreen(BuildBrowserScreen parent, String build, Action action, List<ClientPlacement> placements) {
		super(Component.literal(action.header + build));
		this.parent = parent;
		this.build = build;
		this.action = action;
		this.placements = List.copyOf(placements);
	}

	@Override
	protected void init() {
		// The overlay is drawn behind the window, so it has to be laid out for the screen as it is now.
		parent.resize(width, height);
		Layout layout = layout();
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
			.bounds(width / 2 - 40, layout.listBottom() + (FOOTER - 20) / 2, 80, 20)
			.build());
		scroll = Mth.clamp(scroll, 0.0, maxScroll());
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
		graphics.centeredText(font, fit(title.getString(), layout.width() - 2 * PADDING), width / 2, layout.top() + (HEADER - font.lineHeight) / 2 + 1, WHITE);

		ClientPlacement selected = ClientPlacements.selected();
		@Nullable ClientPlacement hovered = at(mouseX, mouseY);
		int rowLeft = layout.left() + PADDING;
		int rowRight = layout.right() - PADDING;
		graphics.enableScissor(rowLeft, layout.listTop(), rowRight, layout.listBottom());
		for (int i = 0; i < placements.size(); i++) {
			int y = layout.listTop() + i * ROW - (int) scroll;
			if (y + ROW < layout.listTop() || y > layout.listBottom()) {
				continue;
			}
			ClientPlacement placement = placements.get(i);
			if (placement == hovered) {
				graphics.fill(rowLeft, y, rowRight, y + ROW, ROW_HOVERED);
			}
			String where = where(placement);
			int textY = y + (ROW - font.lineHeight) / 2 + 1;
			int whereWidth = font.width(where);
			graphics.text(font, where, rowRight - 4 - whereWidth, textY, GREY);
			String name = fit(placement.placement(), rowRight - rowLeft - 8 - whereWidth - PADDING);
			boolean isSelected = selected != null && selected.label().equals(placement.label());
			graphics.text(font, name, rowLeft + 4, textY, isSelected ? YELLOW : WHITE);
		}
		graphics.disableScissor();

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** Where {@code placement}'s box starts, and in which dimension if not the overworld, e.g. {@code 100, 64, -30}. */
	private static String where(ClientPlacement placement) {
		String corner = placement.box().min().toShortString();
		return placement.dimension().equals(Level.OVERWORLD) ? corner : corner + " in " + placement.dimension().identifier().getPath();
	}

	/** {@code text} cut down to {@code width} pixels, ending in an ellipsis if anything was cut. */
	private String fit(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0 || minecraft == null) {
			return false;
		}
		ClientPlacement placement = at(event.x(), event.y());
		if (placement != null) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			run(placement);
			return true;
		}
		Layout layout = layout();
		if (event.x() < layout.left() || event.x() >= layout.right() || event.y() < layout.top() || event.y() >= layout.bottom()) {
			onClose();
			return true;
		}
		return false;
	}

	/** Closes the window and the overlay and runs the command for {@code placement}, as if typed. */
	private void run(ClientPlacement placement) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft != null) {
			minecraft.setScreen(null);
		}
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand(action.command() + " " + build + " " + placement.placement());
		}
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

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * ROW, 0.0, maxScroll());
		return true;
	}

	/** The placement whose row is under {@code (x, y)}, or null. */
	private @Nullable ClientPlacement at(double x, double y) {
		Layout layout = layout();
		if (x < layout.left() + PADDING || x >= layout.right() - PADDING || y < layout.listTop() || y >= layout.listBottom()) {
			return null;
		}
		int index = (int) ((y - layout.listTop() + (int) scroll) / ROW);
		return index >= 0 && index < placements.size() ? placements.get(index) : null;
	}

	private double maxScroll() {
		Layout layout = layout();
		return Math.max(0, placements.size() * ROW - (layout.listBottom() - layout.listTop()));
	}

	/** The window as wide as its header and rows need and tall enough for every row, as far as the screen allows. */
	private Layout layout() {
		int widest = font.width(title) + 2 * PADDING;
		for (ClientPlacement placement : placements) {
			widest = Math.max(widest, font.width(placement.placement()) + font.width(where(placement)) + 2 * PADDING + 8 + PADDING);
		}
		int panelWidth = Math.min(Math.max(MIN_WIDTH, widest), width - 2 * MARGIN);
		int rows = Mth.clamp((height - 2 * MARGIN - HEADER - FOOTER) / ROW, 1, Math.max(1, placements.size()));
		int panelHeight = HEADER + rows * ROW + FOOTER;
		return new Layout((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
	}

	/** Where the window is: {@code width} by {@code height} pixels with its top left corner at {@code (left, top)}. */
	private record Layout(int left, int top, int width, int height) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}

		int listTop() {
			return top + HEADER;
		}

		int listBottom() {
			return bottom() - FOOTER;
		}
	}

	/** The row of the placement called {@code name} as it is laid out now, for tests to click; null if there is none. */
	public @Nullable ScreenRectangle rowOf(String name) {
		Layout layout = layout();
		for (int i = 0; i < placements.size(); i++) {
			if (placements.get(i).placement().equals(name)) {
				return new ScreenRectangle(layout.left() + PADDING, layout.listTop() + i * ROW - (int) scroll, layout.width() - 2 * PADDING, ROW);
			}
		}
		return null;
	}
}
