package tony.mcvcs.client.browser;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.network.BuildSummary;

/**
 * A window over the builds overlay with a grid of one build's versions, newest first, each showing its turning
 * preview, its number and its tags, the newest marked {@code Latest}. A version bigger than
 * {@link ClientConfig.Settings#autoDownloadLimit} shows a placeholder and, while hovered, a {@code Download} button
 * for its preview. Escape or a click outside the window goes back to the overlay; the overlay's key closes both.
 * Subclasses draw the header above the grid, put their widgets in the footer below it and say what a click on a
 * version does.
 */
abstract class VersionGridScreen extends Screen {
	/** Label of the button a hovered version shows while its preview waits to be downloaded. */
	public static final String DOWNLOAD = "Show preview";
	/** What marks the newest version. */
	public static final String LATEST = "Latest";

	protected static final int GAP = BuildBrowserScreen.GAP;
	private static final int LABEL = BuildBrowserScreen.LABEL;
	/** Space kept between the window and the edges of the screen. */
	private static final int MARGIN = 16;
	private static final int MIN_WIDTH = 160;
	/** The smallest the cells shrink to on a short screen. */
	private static final int MIN_CELL = 48;

	protected static final int WHITE = 0xFFFFFFFF;
	protected static final int GREY = 0xFFA0A0A0;
	protected static final int RED = 0xFFFF6B6B;
	protected static final int GREEN = 0xFF55FF55;
	protected static final int YELLOW = 0xFFFFFF55;
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int PANEL_BORDER = 0xFFB0B0B0;
	private static final int CELL = 0x80000000;
	private static final int CELL_HOVERED = 0xD0304A6A;
	private static final int CELL_HIGHLIGHTED = 0xD0205A30;
	private static final int BORDER = 0xFF3A3A3A;
	private static final int BORDER_HOVERED = 0xFFB0B0B0;

	protected final BuildBrowserScreen parent;
	protected final BuildSummary build;
	/** The build's versions, newest first. */
	protected final List<BuildSummary.Version> versions;
	private final Button download = Button.builder(Component.literal(DOWNLOAD), button -> downloadHovered()).build();
	/** The version the download button is on, or null while it is hidden. */
	private BuildSummary.@Nullable Version downloadFor;
	private double scroll;

	protected VersionGridScreen(Component title, BuildBrowserScreen parent, BuildSummary build) {
		super(title);
		this.parent = parent;
		this.build = build;
		this.versions = build.versions().reversed();
	}

	/** Height of the strip above the grid. */
	protected abstract int headerHeight();

	/** Height of the strip below the grid. */
	protected abstract int footerHeight();

	/** Draws the strip above the grid, from {@code top} down. */
	protected abstract void extractHeader(GuiGraphicsExtractor graphics, Layout layout, int top);

	/** Adds the widgets of the strip below the grid. */
	protected abstract void initFooter(Layout layout);

	/** What a click on {@code version} does. */
	protected abstract void clicked(BuildSummary.Version version);

	/** The tooltip of a hovered version. */
	protected abstract Component tooltip(BuildSummary.Version version);

	/** Whether {@code version}'s cell stands out from the others, as the one chosen. */
	protected boolean highlighted(BuildSummary.Version version) {
		return false;
	}

	/** A word drawn in yellow at the right of {@code version}'s second line, or null for none. */
	protected @Nullable String marker(BuildSummary.Version version) {
		return null;
	}

	/** How wide the window has to be at least, beyond what its grid needs. */
	protected int minWidth() {
		return Math.max(MIN_WIDTH, font.width(title) + 2 * GAP);
	}

	@Override
	protected void init() {
		// The overlay is drawn behind the window, so it has to be laid out for the screen as it is now.
		parent.resize(width, height);
		for (BuildSummary.Version version : versions) {
			if (BuildBrowser.downloadsByItself(version)) {
				BuildBrowser.download(build, version);
			}
		}
		initFooter(layout());
		// Drawn by the grid, over its cell.
		addWidget(download);
		download.visible = false;
		downloadFor = null;
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
		graphics.outline(layout.left() - 1, layout.top() - 1, layout.width() + 2, layout.height() + 2, PANEL_BORDER);
		extractHeader(graphics, layout, layout.top());

		BuildSummary.Version hovered = updateDownload(mouseX, mouseY);
		float yaw = BuildBrowserScreen.yaw();
		graphics.enableScissor(layout.left(), layout.gridTop(), layout.right(), layout.gridBottom());
		for (int i = 0; i < versions.size(); i++) {
			int x = layout.x(i);
			int y = layout.y(i) - (int) scroll;
			if (y + layout.cellHeight() < layout.gridTop() || y > layout.gridBottom()) {
				continue;
			}
			BuildSummary.Version version = versions.get(i);
			cell(graphics, version, x, y, layout.cell(), version == hovered, yaw);
		}
		if (download.visible) {
			download.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		graphics.disableScissor();

		if (hovered != null && !(download.visible && download.isMouseOver(mouseX, mouseY))) {
			graphics.setComponentTooltipForNextFrame(font, List.of(tooltip(hovered)), mouseX, mouseY);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void cell(GuiGraphicsExtractor graphics, BuildSummary.Version version, int x, int y, int size, boolean hovered, float yaw) {
		boolean highlighted = highlighted(version);
		graphics.fill(x, y, x + size, y + size + LABEL, highlighted ? CELL_HIGHLIGHTED : hovered ? CELL_HOVERED : CELL);
		graphics.outline(x - 1, y - 1, size + 2, size + LABEL + 2, highlighted ? GREEN : hovered ? BORDER_HOVERED : BORDER);
		if (highlighted) {
			graphics.outline(x - 2, y - 2, size + 4, size + LABEL + 4, GREEN);
		}
		preview(graphics, version, x, y, size, yaw);

		int line1 = y + size + 2;
		int line2 = line1 + font.lineHeight + 1;
		graphics.text(font, "v" + version.version(), x + 3, line1, WHITE);
		if (version == versions.getFirst()) {
			graphics.text(font, LATEST, x + size - 3 - font.width(LATEST), line1, GREEN);
		}
		String marker = marker(version);
		int markerWidth = marker == null ? 0 : font.width(marker) + 4;
		if (marker != null) {
			graphics.text(font, marker, x + size - 3 - font.width(marker), line2, YELLOW);
		}
		if (!version.tags().isEmpty()) {
			graphics.text(font, fit(String.join(", ", version.tags()), size - 6 - markerWidth), x + 3, line2, GREY);
		}
	}

	/** The preview of {@code version}, or what is keeping it, on the square at the top of its cell. */
	private void preview(GuiGraphicsExtractor graphics, BuildSummary.Version version, int x, int y, int size, float yaw) {
		int centerX = x + size / 2;
		int centerY = y + size / 2;
		Thumbnail thumbnail = BuildBrowser.thumbnail(build, version);
		if (thumbnail == null) {
			wrapped(graphics, "Large build (" + String.format("%,d", version.extent().volume()) + " blocks)", x, y, size, GREY);
			return;
		}
		switch (thumbnail.state()) {
			case REQUESTED -> loading(graphics, centerX, centerY, "Loading");
			case DOWNLOADING -> loading(graphics, centerX, centerY, "Loading " + (int) (thumbnail.progress() * 100) + "%");
			case MESHING -> loading(graphics, centerX, centerY, "Loading");
			case FAILED -> wrapped(graphics, thumbnail.failure(), x, y, size, RED);
			case READY -> {
				ThumbnailMesh mesh = thumbnail.mesh();
				if (mesh == null || mesh.isEmpty()) {
					graphics.centeredText(font, "Empty", centerX, centerY - font.lineHeight / 2, GREY);
				} else {
					graphics.guiRenderState.addPicturesInPictureState(
						new BuildPreviewRenderState(mesh, yaw, x, y, x + size, y + size, graphics.scissorStack.peek()));
				}
			}
		}
	}

	/** {@code text} wrapped to the cell's width and centred in its preview area. */
	private void wrapped(GuiGraphicsExtractor graphics, @Nullable String text, int x, int y, int size, int color) {
		var lines = font.split(Component.literal(text == null ? "" : text), size - 8);
		int top = y + (size - lines.size() * font.lineHeight) / 2;
		for (int i = 0; i < lines.size(); i++) {
			graphics.centeredText(font, lines.get(i), x + size / 2, top + i * font.lineHeight, color);
		}
	}

	private void loading(GuiGraphicsExtractor graphics, int x, int y, String text) {
		graphics.centeredText(font, LoadingDotsText.get(Util.getMillis()), x, y - font.lineHeight, GREY);
		graphics.centeredText(font, text, x, y + 2, GREY);
	}

	protected String fit(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}

	/**
	 * Puts the download button at the bottom of the preview of the version under {@code (x, y)} if its preview has
	 * not been asked for, or hides it, and says which version is under {@code (x, y)}.
	 */
	private BuildSummary.@Nullable Version updateDownload(double x, double y) {
		BuildSummary.Version version = at(x, y);
		ScreenRectangle cell = version == null ? null : cellOf(version.version());
		if (version == null || cell == null || BuildBrowser.thumbnail(build, version) != null) {
			download.visible = false;
			downloadFor = null;
			return version;
		}
		downloadFor = version;
		download.visible = true;
		int buttonWidth = Math.min(font.width(DOWNLOAD) + 16, cell.width() - 4);
		download.setRectangle(buttonWidth, 20, cell.left() + (cell.width() - buttonWidth) / 2, cell.top() + cell.width() - 24);
		return version;
	}

	private void downloadHovered() {
		if (downloadFor != null) {
			BuildBrowser.download(build, downloadFor);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		updateDownload(event.x(), event.y());
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0 || minecraft == null) {
			return false;
		}
		BuildSummary.Version version = at(event.x(), event.y());
		if (version != null) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			clicked(version);
			return true;
		}
		Layout layout = layout();
		if (event.x() < layout.left() || event.x() >= layout.right() || event.y() < layout.top() || event.y() >= layout.bottom()) {
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

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * (layout().cellHeight() + GAP) / 2.0, 0.0, maxScroll());
		return true;
	}

	/** The version whose cell is under {@code (x, y)}, or null. */
	private BuildSummary.@Nullable Version at(double x, double y) {
		Layout layout = layout();
		if (y < layout.gridTop() || y >= layout.gridBottom()) {
			return null;
		}
		for (int i = 0; i < versions.size(); i++) {
			int cellX = layout.x(i);
			int cellY = layout.y(i) - (int) scroll;
			if (x >= cellX && x < cellX + layout.cell() && y >= cellY && y < cellY + layout.cellHeight()) {
				return versions.get(i);
			}
		}
		return null;
	}

	private double maxScroll() {
		Layout layout = layout();
		return Math.max(0, layout.rows() * (layout.cellHeight() + GAP) + GAP - (layout.gridBottom() - layout.gridTop()));
	}

	/**
	 * The window: as many columns of cells as there are versions, as far as the screen allows, and tall enough for
	 * every row, as far as the screen allows.
	 */
	protected Layout layout() {
		int header = headerHeight();
		int footer = footerHeight();
		// Smaller than the configured size if a whole row would not fit otherwise, so the labels under the previews show.
		int cell = Math.min(ClientConfig.settings().cellSize(), Math.max(MIN_CELL, height - 2 * MARGIN - header - footer - LABEL - 2 * GAP));
		int columns = columns();
		int rows = (versions.size() + columns - 1) / columns;
		int panelWidth = panelWidth();
		int gridHeight = Math.max(0, Math.min(rows * (cell + LABEL + GAP) + GAP, height - 2 * MARGIN - header - footer));
		int panelHeight = header + gridHeight + footer;
		int left = (width - panelWidth) / 2;
		int gridWidth = columns * cell + (columns - 1) * GAP;
		return new Layout(left, (height - panelHeight) / 2, panelWidth, panelHeight, header, footer, cell, columns, rows, left + (panelWidth - gridWidth) / 2);
	}

	private int columns() {
		int cell = ClientConfig.settings().cellSize();
		int maxColumns = Math.max(1, (width - 2 * MARGIN - GAP) / (cell + GAP));
		return Mth.clamp(versions.size(), 1, maxColumns);
	}

	/** How wide the window is: as its grid or {@link #minWidth()} needs, as far as the screen allows. */
	protected int panelWidth() {
		int cell = ClientConfig.settings().cellSize();
		return Math.min(Math.max(minWidth(), columns() * (cell + GAP) + GAP), width - 2 * MARGIN);
	}

	/**
	 * Where the window is, {@code width} by {@code height} pixels with its top left corner at {@code (left, top)}, with
	 * {@code header} pixels above its grid and {@code footer} below it, and its grid of {@code columns} cells across,
	 * starting {@code gridLeft} pixels in.
	 */
	protected record Layout(int left, int top, int width, int height, int header, int footer, int cell, int columns, int rows, int gridLeft) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}

		int gridTop() {
			return top + header;
		}

		int gridBottom() {
			return bottom() - footer;
		}

		int cellHeight() {
			return cell + LABEL;
		}

		int x(int index) {
			return gridLeft + (index % columns) * (cell + GAP);
		}

		/** Top of the cell before scrolling. */
		int y(int index) {
			return gridTop() + GAP + (index / columns) * (cellHeight() + GAP);
		}
	}

	/** The cell of version {@code version} as it is laid out now, for tests to click; null if there is none. */
	public @Nullable ScreenRectangle cellOf(int version) {
		Layout layout = layout();
		for (int i = 0; i < versions.size(); i++) {
			if (versions.get(i).version() == version) {
				return new ScreenRectangle(layout.x(i), layout.y(i) - (int) scroll, layout.cell(), layout.cellHeight());
			}
		}
		return null;
	}
}
