package tony.mcvcs.client.browser;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.diff.DiffHighlightRenderer;
import tony.mcvcs.network.BuildSummary;
import tony.mcvcs.network.CommitPreviewPayload;

/**
 * The builds overlay's {@code Commit} window, for the selected placement: its header names the placement and the
 * version committing it would make, and says how many blocks were added, removed and changed since the version it
 * holds; below that, the blocks in its box turn as the new version would look, the added ones tinted green, the changed
 * ones yellow, and red boxes where blocks were removed, the way {@code /vcs diff} colours them. Dragging the preview
 * turns it by hand. {@code Commit} closes the window and the overlay and runs {@code /vcs commit}, with the tag typed
 * if any, as if typed; it is greyed out while nothing changed. {@code Show modified blocks} closes both and runs
 * {@code /vcs diff}, which highlights the changes in the world. {@code Cancel}, Escape or a click outside the window
 * goes back to the overlay.
 * <p>
 * What the preview shows is asked for when the window opens and dropped when it closes, see {@link CommitPreview}.
 */
public final class CommitScreen extends Screen {
	/** Labels of the window's buttons. */
	public static final String COMMIT = "Commit";
	public static final String CANCEL = "Cancel";
	public static final String SHOW_MODIFIED = CheckoutScreen.SHOW_MODIFIED;

	private static final int WIDTH = 320;
	private static final int MARGIN = 16;
	private static final int PADDING = 8;
	private static final int ROW = 20;
	private static final int BUTTON_WIDTH = 80;
	/** How far the preview turns per pixel it is dragged, in degrees. */
	private static final float DRAG_DEGREES = 0.8f;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6B6B;
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int BORDER = 0xFFB0B0B0;
	private static final int PREVIEW = 0x80000000;
	private static final int PREVIEW_BORDER = 0xFF3A3A3A;

	private final BuildBrowserScreen parent;
	private final BuildSummary build;
	private final ClientPlacement placement;
	private @Nullable EditBox tagField;
	private @Nullable Button commit;
	private @Nullable Button showModified;
	/** How far the preview has been dragged round, in degrees, on top of how far it turns by itself. */
	private float dragged;
	private boolean dragging;

	public CommitScreen(BuildBrowserScreen parent, BuildSummary build, ClientPlacement placement) {
		super(Component.literal("Commit placement " + placement.label() + " as v" + (build.version() + 1)));
		this.parent = parent;
		this.build = build;
		this.placement = placement;
	}

	@Override
	public void added() {
		BuildBrowser.requestCommitPreview(placement);
	}

	@Override
	public void removed() {
		BuildBrowser.dropCommitPreview();
	}

	@Override
	protected void init() {
		parent.resize(width, height);
		Layout layout = layout();
		String tag = tagField == null ? "" : tagField.getValue();
		EditBox field = new EditBox(font, layout.left() + PADDING, layout.tagTop(), layout.width() - 2 * PADDING, ROW, Component.literal("Tag"));
		field.setMaxLength(64);
		field.setHint(Component.literal("Tag (optional)").withStyle(ChatFormatting.DARK_GRAY));
		field.setValue(tag);
		field.setResponder(value -> updateCommit());
		addRenderableWidget(field);
		tagField = field;

		int commitWidth = Math.max(BUTTON_WIDTH, font.width(COMMIT + " v" + (build.version() + 1)) + 16);
		int showWidth = font.width(SHOW_MODIFIED) + 16;
		int cancelWidth = Math.max(40, Math.min(BUTTON_WIDTH, layout.width() - 2 * PADDING - commitWidth - showWidth - 2 * PADDING));
		int left = width / 2 - (commitWidth + PADDING + showWidth + PADDING + cancelWidth) / 2;
		commit = addRenderableWidget(Button.builder(Component.literal(COMMIT + " v" + (build.version() + 1)), button -> commit())
			.bounds(left, layout.buttonsTop(), commitWidth, ROW)
			.build());
		showModified = addRenderableWidget(Button.builder(Component.literal(SHOW_MODIFIED), button -> closeAndRun("vcs diff"))
			.bounds(left + commitWidth + PADDING, layout.buttonsTop(), showWidth, ROW)
			.tooltip(Tooltip.create(Component.literal("Close the overlay and highlight the blocks changed since " + placement.label() + " last held a version")))
			.build());
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(left + commitWidth + PADDING + showWidth + PADDING, layout.buttonsTop(), cancelWidth, ROW)
			.build());
		updateCommit();
	}

	/** What the server said, or null while it is awaited. */
	public @Nullable CommitPreviewPayload summary() {
		CommitPreview preview = BuildBrowser.commitPreview();
		return preview == null ? null : preview.summary();
	}

	/** The tag typed, without spaces around it; empty for none. */
	private String tag() {
		return tagField == null ? "" : tagField.getValue().trim();
	}

	/** Why {@code tag} cannot be given to the new version, or null if it can. */
	private @Nullable String tagRefusal(String tag) {
		if (tag.isEmpty()) {
			return null;
		}
		if (!Build.isValidTag(tag)) {
			return "A tag may only contain letters, digits, -, _, + and dots, and may not be digits alone";
		}
		return build.versions().stream().filter(version -> version.tags().contains(tag)).findFirst()
			.map(version -> "Tag " + tag + " already names v" + version.version())
			.orElse(null);
	}

	/** Lets {@code Commit} be pressed only once something changed and the tag, if any, can be given. */
	private void updateCommit() {
		if (commit == null) {
			return;
		}
		CommitPreviewPayload summary = summary();
		String refusal = tagRefusal(tag());
		boolean nothing = summary != null && summary.total() == 0;
		if (showModified != null) {
			showModified.active = summary != null && !nothing;
		}
		commit.active = summary != null && !nothing && refusal == null;
		String tooltip = summary == null ? "Checking what changed"
			: nothing ? "Nothing to commit: " + placement.label() + " is the same as " + held()
			: refusal != null ? refusal
			: "Save " + placement.label() + " as v" + (build.version() + 1) + (tag().isEmpty() ? "" : ", tagged " + tag());
		commit.setTooltip(Tooltip.create(Component.literal(tooltip)));
	}

	/** The version the placement holds, with its tags. */
	private String held() {
		return build.versions().stream().filter(version -> version.version() == placement.head())
			.findFirst().map(BuildSummary.Version::label).orElse("v" + placement.head());
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
		// The counts arrive after the window opens.
		updateCommit();
		Layout layout = layout();
		graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), PANEL);
		graphics.outline(layout.left() - 1, layout.top() - 1, layout.width() + 2, layout.height() + 2, BORDER);
		int y = layout.top() + PADDING;
		for (FormattedCharSequence line : headerLines(layout.width() - 2 * PADDING)) {
			graphics.centeredText(font, line, width / 2, y, WHITE);
			y += font.lineHeight + 2;
		}

		ScreenRectangle square = layout.preview();
		graphics.fill(square.left(), square.top(), square.right(), square.bottom(), PREVIEW);
		graphics.outline(square.left() - 1, square.top() - 1, square.width() + 2, square.height() + 2, PREVIEW_BORDER);
		graphics.enableScissor(square.left(), square.top(), square.right(), square.bottom());
		preview(graphics, square);
		graphics.disableScissor();
		legend(graphics, layout.legendTop());
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/** The heading, then what changed, wrapped to {@code width}. */
	private List<FormattedCharSequence> headerLines(int width) {
		List<FormattedCharSequence> lines = new ArrayList<>(font.split(heading(), width));
		lines.addAll(font.split(status(), width));
		return lines;
	}

	/** "Commit placement <placement> as v<next>", with the names in yellow. */
	private Component heading() {
		return Component.literal("Commit placement ")
			.append(Component.literal(placement.label()).withStyle(ChatFormatting.YELLOW))
			.append(" as ")
			.append(Component.literal("v" + (build.version() + 1)).withStyle(ChatFormatting.YELLOW));
	}

	/** How many blocks were added, removed and changed since the version the placement holds. */
	private Component status() {
		CommitPreviewPayload summary = summary();
		MutableComponent text = Component.literal("Since " + held() + ": ").withStyle(ChatFormatting.GRAY);
		if (summary == null) {
			return text.append("checking what changed...");
		}
		if (!summary.known()) {
			return text.append(Component.literal("could not compare").withStyle(ChatFormatting.RED));
		}
		if (summary.total() == 0) {
			return text.append(Component.literal("nothing changed, so there is nothing to commit").withStyle(ChatFormatting.GOLD));
		}
		return text.append(count(summary.added(), "added", ChatFormatting.GREEN)).append(", ")
			.append(count(summary.removed(), "removed", ChatFormatting.RED)).append(", ")
			.append(count(summary.changed(), "changed", ChatFormatting.YELLOW));
	}

	private static Component count(int count, String what, ChatFormatting color) {
		return Component.literal(String.format("%,d", count) + " " + what).withStyle(color);
	}

	/** The preview of the new version, or what is keeping it, on {@code square}. */
	private void preview(GuiGraphicsExtractor graphics, ScreenRectangle square) {
		int centerX = square.left() + square.width() / 2;
		int centerY = square.top() + square.height() / 2;
		CommitPreview preview = BuildBrowser.commitPreview();
		Thumbnail thumbnail = preview == null ? null : preview.thumbnail();
		if (thumbnail == null) {
			return;
		}
		switch (thumbnail.state()) {
			case REQUESTED -> loading(graphics, centerX, centerY, "Reading the placement");
			case DOWNLOADING -> loading(graphics, centerX, centerY, "Downloading " + (int) (thumbnail.progress() * 100) + "%");
			case MESHING -> loading(graphics, centerX, centerY, "Preparing");
			case FAILED -> graphics.centeredText(font, String.valueOf(thumbnail.failure()), centerX, centerY - font.lineHeight / 2, RED);
			case READY -> {
				ThumbnailMesh mesh = thumbnail.mesh();
				if (mesh == null || mesh.isEmpty()) {
					graphics.centeredText(font, "Nothing to show", centerX, centerY - font.lineHeight / 2, GREY);
				} else {
					graphics.guiRenderState.addPicturesInPictureState(new BuildPreviewRenderState(mesh, yaw(),
						square.left(), square.top(), square.right(), square.bottom(), graphics.scissorStack.peek()));
				}
			}
		}
	}

	/** How far the preview has turned: by itself, as the overlay's do, and by however far it was dragged. */
	private float yaw() {
		return ((BuildBrowserScreen.yaw() + dragged) % 360.0f + 360.0f) % 360.0f;
	}

	/** What the colours mean, centred on one line. */
	private void legend(GuiGraphicsExtractor graphics, int top) {
		String[] labels = {"Added", "Removed", "Changed"};
		int[] colors = {DiffHighlightRenderer.ADDED_COLOR, DiffHighlightRenderer.REMOVED_COLOR, DiffHighlightRenderer.CHANGED_COLOR};
		int swatch = font.lineHeight - 1;
		int gap = 12;
		int total = -gap;
		for (String label : labels) {
			total += swatch + 3 + font.width(label) + gap;
		}
		int x = (width - total) / 2;
		for (int i = 0; i < labels.length; i++) {
			graphics.fill(x, top, x + swatch, top + swatch, colors[i]);
			graphics.text(font, labels[i], x + swatch + 3, top, GREY);
			x += swatch + 3 + font.width(labels[i]) + gap;
		}
	}

	private void loading(GuiGraphicsExtractor graphics, int x, int y, String text) {
		graphics.centeredText(font, LoadingDotsText.get(Util.getMillis()), x, y - font.lineHeight, GREY);
		graphics.centeredText(font, text, x, y + 2, GREY);
	}

	/** Closes the window and the overlay and runs {@code /vcs commit}, with the tag if one was typed, as if typed. */
	private void commit() {
		if (commit == null || !commit.active || minecraft == null) {
			return;
		}
		String tag = tag();
		closeAndRun("vcs commit" + (tag.isEmpty() ? "" : " " + tag));
	}

	/** Closes the window and the overlay and runs {@code command} as if typed. */
	private void closeAndRun(String command) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft != null) {
			minecraft.gui.setScreen(null);
		}
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand(command);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
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
		if (event.isConfirmation() && tagField != null && tagField.isFocused()) {
			commit();
			return true;
		}
		// Typed into the tag field, the overlay's key is just a letter.
		if (BuildBrowserKey.KEY.matches(event) && (tagField == null || !tagField.isFocused()) && minecraft != null) {
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

	/** The button or field labelled {@code label}, or starting with it, for tests to click; null if there is none. */
	public @Nullable AbstractWidget widgetOf(String label) {
		return children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().startsWith(label))
			.map(AbstractWidget.class::cast)
			.findFirst().orElse(null);
	}

	/**
	 * The window: the header, a square preview as big as the screen allows up to the window's width, the legend, the
	 * tag field and the buttons, one under the other.
	 */
	private Layout layout() {
		int panelWidth = Math.min(WIDTH, width - 2 * MARGIN);
		int header = PADDING + headerLines(panelWidth - 2 * PADDING).size() * (font.lineHeight + 2) + PADDING / 2;
		int below = PADDING + font.lineHeight + PADDING + ROW + PADDING + ROW + PADDING;
		int side = Math.max(32, Math.min(panelWidth - 2 * PADDING, height - 2 * MARGIN - header - below));
		int panelHeight = header + side + below;
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		int previewTop = top + header;
		int legendTop = previewTop + side + PADDING;
		int tagTop = legendTop + font.lineHeight + PADDING;
		return new Layout(left, top, panelWidth, panelHeight,
			new ScreenRectangle(left + (panelWidth - side) / 2, previewTop, side, side), legendTop, tagTop, tagTop + ROW + PADDING);
	}

	private record Layout(int left, int top, int width, int height, ScreenRectangle preview, int legendTop, int tagTop, int buttonsTop) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}
	}
}
