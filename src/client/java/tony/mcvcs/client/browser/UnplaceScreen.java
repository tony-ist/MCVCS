package tony.mcvcs.client.browser;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.command.VcsCommand;
import tony.mcvcs.command.VcsCommandUnplace;
import tony.mcvcs.network.CommitPreviewPayload;

/**
 * The builds overlay's {@code Unplace} window, for the selected placement: asks whether to remove it from the world,
 * over a preview of the blocks in its box as they stand, turning and draggable as the commit window's is, and warns
 * when it holds unsaved changes. A checkbox, unticked to begin with, keeps its blocks standing and only stops the
 * placement being tracked; unticked with unsaved changes, {@code Remove} waits for the {@link #CLEAR} checkbox to be
 * ticked. {@code Remove} runs {@code /vcs unplace -f}, with {@code -k} if ticked, as if typed, and goes
 * back to the overlay, whose list is asked for again once the placement is gone. {@code Cancel}, Escape or a click
 * outside the window goes back to the overlay; the overlay's key closes both.
 * <p>
 * The preview is the commit window's, asked for when the window opens and dropped when it closes, see {@link CommitPreview}.
 */
public final class UnplaceScreen extends Screen {
	/** Labels of the window's buttons and checkbox. */
	public static final String REMOVE = "Remove";
	public static final String CANCEL = "Cancel";
	public static final String KEEP = "Keep its blocks in the world";
	public static final String CLEAR = "Clear all unsaved changes";

	private static final int WIDTH = 260;
	private static final int MARGIN = 16;
	private static final int PADDING = 8;
	private static final int ROW = 20;
	private static final int BUTTON_WIDTH = 80;
	/** The biggest the preview gets, however much room the screen has. */
	private static final int MAX_PREVIEW = 160;
	private static final float DRAG_DEGREES = 0.8f;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6B6B;
	private static final int GOLD = 0xFFFFAA00;
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int BORDER = 0xFFB0B0B0;
	private static final int PREVIEW = 0x80000000;
	private static final int PREVIEW_BORDER = 0xFF3A3A3A;

	private final BuildBrowserScreen parent;
	private final ClientPlacement placement;
	private boolean keep;
	/** Whether the player ticked {@link #CLEAR}, which {@code Remove} waits for when there are unsaved changes. */
	private boolean clear;
	private @Nullable Button removeButton;
	/** The layout the widgets were placed for, to place them again when the warning arrives. */
	private @Nullable Layout placed;
	private float dragged;
	private boolean dragging;

	public UnplaceScreen(BuildBrowserScreen parent, ClientPlacement placement) {
		super(Component.literal("Remove " + placement.label() + " from the world?"));
		this.parent = parent;
		this.placement = placement;
	}

	@Override
	public void added() {
		BuildBrowser.requestCommitPreview(placement, false);
	}

	@Override
	public void removed() {
		BuildBrowser.dropCommitPreview();
	}

	@Override
	protected void init() {
		parent.resize(width, height);
		Layout layout = layout();
		placed = layout;
		Checkbox checkbox = Checkbox.builder(Component.literal(KEEP), font)
			.pos(layout.left() + PADDING, layout.checkboxTop())
			.maxWidth(layout.width() - 2 * PADDING)
			.selected(keep)
			// Shows or hides the box to clear unsaved changes, which the next frame lays out.
			.onValueChange((box, value) -> keep = value)
			.build();
		checkbox.setTooltip(Tooltip.create(Component.literal("The blocks stay where they are, but this copy stops being tracked")));
		addRenderableWidget(checkbox);
		if (layout.clearTop() >= 0) {
			addRenderableWidget(Checkbox.builder(Component.literal(CLEAR).withStyle(ChatFormatting.GOLD), font)
				.pos(layout.left() + PADDING, layout.clearTop())
				.maxWidth(layout.width() - 2 * PADDING)
				.selected(clear)
				.onValueChange((box, value) -> {
					clear = value;
					updateRemove();
				})
				.build());
		}

		int left = width / 2 - BUTTON_WIDTH - PADDING / 2;
		removeButton = addRenderableWidget(Button.builder(Component.literal(REMOVE).withStyle(ChatFormatting.RED), button -> remove())
			.bounds(left, layout.buttonsTop(), BUTTON_WIDTH, ROW)
			.build());
		updateRemove();
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(left + BUTTON_WIDTH + PADDING, layout.buttonsTop(), BUTTON_WIDTH, ROW)
			.build());
	}

	/** How many blocks differ from the version the placement holds, as the server said, or null while it is awaited. */
	public @Nullable CommitPreviewPayload summary() {
		CommitPreview preview = BuildBrowser.commitPreview();
		return preview == null ? null : preview.summary();
	}

	/**
	 * Whether {@link #CLEAR} is shown and has to be ticked before the copy can be removed: when it has, or may have,
	 * unsaved changes whose blocks are about to be cleared, that is unless {@link #KEEP} is ticked.
	 */
	private boolean needsClear() {
		CommitPreviewPayload summary = summary();
		return !keep && summary != null && (!summary.known() || summary.total() > 0);
	}

	/** {@code Remove} works once the changes are known, and only once {@link #CLEAR} is ticked if it is shown. */
	private void updateRemove() {
		if (removeButton != null) {
			removeButton.active = summary() != null && (clear || !needsClear());
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		parent.extractBackground(graphics, -1, -1, partialTick);
		graphics.nextStratum();
		parent.extractRenderState(graphics, -1, -1, partialTick);
		graphics.nextStratum();
		graphics.fill(0, 0, width, height, DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// The warning and its checkbox arrive after the window opens and may make it taller.
		Layout layout = layout();
		if (!layout.equals(placed)) {
			rebuildWidgets();
		}
		updateRemove();
		graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), PANEL);
		graphics.outline(layout.left() - 1, layout.top() - 1, layout.width() + 2, layout.height() + 2, BORDER);
		int y = layout.top() + PADDING;
		for (Line line : lines(layout.width() - 2 * PADDING)) {
			graphics.centeredText(font, line.text(), width / 2, y, line.color());
			y += font.lineHeight + 2;
		}

		ScreenRectangle square = layout.preview();
		graphics.fill(square.left(), square.top(), square.right(), square.bottom(), PREVIEW);
		graphics.outline(square.left() - 1, square.top() - 1, square.width() + 2, square.height() + 2, PREVIEW_BORDER);
		graphics.enableScissor(square.left(), square.top(), square.right(), square.bottom());
		preview(graphics, square);
		graphics.disableScissor();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private record Line(FormattedCharSequence text, int color) {
	}

	/** The question, what stays, and the unsaved changes that would be lost if there are any, wrapped to {@code width}. */
	private List<Line> lines(int width) {
		List<Line> lines = new ArrayList<>();
		Component question = Component.literal("Remove ")
			.append(Component.literal(placement.label()).withStyle(ChatFormatting.YELLOW))
			.append(" from the world?");
		font.split(question, width).forEach(line -> lines.add(new Line(line, WHITE)));
		font.split(Component.literal("Its saved versions stay, so you can place it again later."), width)
			.forEach(line -> lines.add(new Line(line, GREY)));
		CommitPreviewPayload summary = summary();
		// Kept, the changed blocks still stand; they are only no longer part of the build.
		String fate = keep ? " will stay in the world but won't be part of the build." : " will be lost.";
		String warning = summary == null ? null
			: !summary.known() ? "Couldn't check for unsaved changes. Any" + fate
			: summary.total() == 0 ? null
			: "Its " + String.format("%,d", summary.total()) + (summary.total() == 1 ? " unsaved change" : " unsaved changes") + fate;
		if (warning != null) {
			font.split(Component.literal(warning), width).forEach(line -> lines.add(new Line(line, GOLD)));
		}
		return lines;
	}

	/** The blocks in the placement's box, or what is keeping them, on {@code square}. */
	private void preview(GuiGraphicsExtractor graphics, ScreenRectangle square) {
		int centerX = square.left() + square.width() / 2;
		int centerY = square.top() + square.height() / 2;
		CommitPreview preview = BuildBrowser.commitPreview();
		Thumbnail thumbnail = preview == null ? null : preview.thumbnail();
		if (thumbnail == null) {
			return;
		}
		switch (thumbnail.state()) {
			case REQUESTED, MESHING -> loading(graphics, centerX, centerY, "Loading");
			case DOWNLOADING -> loading(graphics, centerX, centerY, "Loading " + (int) (thumbnail.progress() * 100) + "%");
			case FAILED -> graphics.centeredText(font, String.valueOf(thumbnail.failure()), centerX, centerY - font.lineHeight / 2, RED);
			case READY -> {
				ThumbnailMesh mesh = thumbnail.mesh();
				if (mesh == null || mesh.isEmpty()) {
					graphics.centeredText(font, "Empty", centerX, centerY - font.lineHeight / 2, GREY);
				} else {
					float yaw = ((BuildBrowserScreen.yaw() + dragged) % 360.0f + 360.0f) % 360.0f;
					graphics.guiRenderState.addPicturesInPictureState(new BuildPreviewRenderState(mesh, yaw,
						square.left(), square.top(), square.right(), square.bottom(), graphics.scissorStack.peek()));
				}
			}
		}
	}

	private void loading(GuiGraphicsExtractor graphics, int x, int y, String text) {
		graphics.centeredText(font, LoadingDotsText.get(Util.getMillis()), x, y - font.lineHeight, GREY);
		graphics.centeredText(font, text, x, y + 2, GREY);
	}

	/** The command the window runs, without the slash: it has asked already, so the server is not to ask again. */
	static String unplaceCommand(boolean keep) {
		return "vcs unplace " + (keep ? VcsCommandUnplace.KEEP + " " : "") + VcsCommand.FORCE;
	}

	/**
	 * Goes back to the overlay and runs {@code /vcs unplace} as if typed, so the server checks the permission and answers
	 * in chat the way it does for it. The list is asked for again once the placement is gone.
	 */
	private void remove() {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		onClose();
		if (player != null) {
			player.connection.sendCommand(unplaceCommand(keep));
			BuildBrowser.refreshAfterCommand();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		Layout layout = layout();
		ScreenRectangle square = layout.preview();
		if (event.x() >= square.left() && event.x() < square.right() && event.y() >= square.top() && event.y() < square.bottom()) {
			dragging = true;
			return true;
		}
		if (event.x() < layout.left() || event.x() >= layout.right() || event.y() < layout.top() || event.y() >= layout.bottom()) {
			onClose();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			dragged += (float) dragX * DRAG_DEGREES;
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BuildBrowserKey.KEY.matches(event) && minecraft != null) {
			minecraft.gui.setScreen(null);
			return true;
		}
		return super.keyPressed(event);
	}

	/** Back to the overlay, which is still as it was. */
	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	/** The button or checkbox labelled {@code label}, for tests to click; null if there is none. */
	public @Nullable AbstractWidget widgetOf(String label) {
		return children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().equals(label))
			.map(AbstractWidget.class::cast)
			.findFirst().orElse(null);
	}

	/**
	 * The window: the text, a square preview as big as the screen allows, the checkbox to keep the blocks, the one to
	 * clear unsaved changes if it is needed, and the buttons, one under the other.
	 */
	private Layout layout() {
		int panelWidth = Math.min(WIDTH, width - 2 * MARGIN);
		int header = PADDING + lines(panelWidth - 2 * PADDING).size() * (font.lineHeight + 2) + PADDING / 2;
		int checkbox = Checkbox.getBoxSize(font);
		int checkboxes = needsClear() ? 2 : 1;
		int below = PADDING + checkboxes * (checkbox + PADDING) + ROW + PADDING;
		int side = Math.max(32, Math.min(Math.min(MAX_PREVIEW, panelWidth - 2 * PADDING), height - 2 * MARGIN - header - below));
		int panelHeight = header + side + below;
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		int previewTop = top + header;
		int checkboxTop = previewTop + side + PADDING;
		int clearTop = checkboxes == 2 ? checkboxTop + checkbox + PADDING : -1;
		int buttonsTop = checkboxTop + checkboxes * (checkbox + PADDING);
		return new Layout(left, top, panelWidth, panelHeight,
			new ScreenRectangle(left + (panelWidth - side) / 2, previewTop, side, side), clearTop, checkboxTop, buttonsTop);
	}

	/** {@code clearTop} is -1 when there is no checkbox to clear unsaved changes. */
	private record Layout(int left, int top, int width, int height, ScreenRectangle preview, int clearTop, int checkboxTop, int buttonsTop) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}
	}
}
