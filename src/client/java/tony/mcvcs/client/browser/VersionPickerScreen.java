package tony.mcvcs.client.browser;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.network.BuildSummary;

/**
 * Asks which version the builds overlay's {@code Place} button means, for a build that has several: a window over the
 * overlay with a grid of the build's versions, newest first, each showing its turning preview, its number and its
 * tags, the newest marked {@code Latest}. Clicking one closes the window and the overlay and runs {@code /vcs place}
 * for that version, as if typed. A version bigger than {@link ClientConfig.Settings#autoDownloadLimit} shows a
 * placeholder and, while hovered, a {@code Download} button for its preview. {@code Cancel}, Escape or a click outside
 * the window goes back to the overlay; the overlay's key closes both.
 */
public final class VersionPickerScreen extends Screen {
	/** Label of the button a hovered version shows while its preview waits to be downloaded. */
	public static final String DOWNLOAD = "Download";
	/** What marks the newest version. */
	public static final String LATEST = "Latest";

	private static final int GAP = BuildBrowserScreen.GAP;
	private static final int LABEL = BuildBrowserScreen.LABEL;
	/** Height of the strip at the top of the window holding its header. */
	private static final int HEADER = 24;
	/** Height of the strip at the bottom of the window holding the cancel button. */
	private static final int FOOTER = 32;
	/** Space kept between the window and the edges of the screen. */
	private static final int MARGIN = 16;
	private static final int MIN_WIDTH = 160;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6B6B;
	private static final int GREEN = 0xFF55FF55;
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int PANEL_BORDER = 0xFFB0B0B0;
	private static final int CELL = 0x80000000;
	private static final int CELL_HOVERED = 0xD0304A6A;
	private static final int BORDER = 0xFF3A3A3A;
	private static final int BORDER_HOVERED = 0xFFB0B0B0;

	private final BuildBrowserScreen parent;
	private final BuildSummary build;
	/** The build's versions, newest first. */
	private final List<BuildSummary.Version> versions;
	private final Button download = Button.builder(Component.literal(DOWNLOAD), button -> downloadHovered()).build();
	/** The version the download button is on, or null while it is hidden. */
	private BuildSummary.@Nullable Version downloadFor;
	private double scroll;

	public VersionPickerScreen(BuildBrowserScreen parent, BuildSummary build) {
		super(Component.literal("Place a version of " + build.name()));
		this.parent = parent;
		this.build = build;
		this.versions = build.versions().reversed();
	}

	/** The command that places {@code version} of {@code build}, without the slash. */
	static String placeCommand(BuildSummary build, BuildSummary.Version version) {
		return "vcs place " + build.name() + " " + version.version();
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
		Layout layout = layout();
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
			.bounds(width / 2 - 40, layout.gridBottom() + (FOOTER - 20) / 2, 80, 20)
			.build());
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
		graphics.centeredText(font, fit(title.getString(), layout.width() - 2 * GAP), width / 2, layout.top() + (HEADER - font.lineHeight) / 2 + 1, WHITE);

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
			graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal("Place a copy of " + hovered.label() + " where you stand")), mouseX, mouseY);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void cell(GuiGraphicsExtractor graphics, BuildSummary.Version version, int x, int y, int size, boolean hovered, float yaw) {
		graphics.fill(x, y, x + size, y + size + LABEL, hovered ? CELL_HOVERED : CELL);
		graphics.outline(x - 1, y - 1, size + 2, size + LABEL + 2, hovered ? BORDER_HOVERED : BORDER);
		preview(graphics, version, x, y, size, yaw);

		int line1 = y + size + 2;
		int line2 = line1 + font.lineHeight + 1;
		String number = "v" + version.version();
		graphics.text(font, number, x + 3, line1, WHITE);
		if (version == versions.getFirst()) {
			graphics.text(font, LATEST, x + size - 3 - font.width(LATEST), line1, GREEN);
		}
		if (!version.tags().isEmpty()) {
			graphics.text(font, fit(String.join(", ", version.tags()), size - 6), x + 3, line2, GREY);
		}
	}

	/** The preview of {@code version}, or what is keeping it, on the square at the top of its cell. */
	private void preview(GuiGraphicsExtractor graphics, BuildSummary.Version version, int x, int y, int size, float yaw) {
		int centerX = x + size / 2;
		int centerY = y + size / 2;
		Thumbnail thumbnail = BuildBrowser.thumbnail(build, version);
		if (thumbnail == null) {
			wrapped(graphics, "Preview not downloaded (" + String.format("%,d", version.extent().volume()) + " blocks)", x, y, size, GREY);
			return;
		}
		switch (thumbnail.state()) {
			case REQUESTED -> loading(graphics, centerX, centerY, "Downloading");
			case DOWNLOADING -> loading(graphics, centerX, centerY, "Downloading " + (int) (thumbnail.progress() * 100) + "%");
			case MESHING -> loading(graphics, centerX, centerY, "Preparing");
			case FAILED -> wrapped(graphics, thumbnail.failure(), x, y, size, RED);
			case READY -> {
				ThumbnailMesh mesh = thumbnail.mesh();
				if (mesh == null || mesh.isEmpty()) {
					graphics.centeredText(font, "Nothing to show", centerX, centerY - font.lineHeight / 2, GREY);
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

	private String fit(String text, int width) {
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
			place(version);
			return true;
		}
		Layout layout = layout();
		if (event.x() < layout.left() || event.x() >= layout.right() || event.y() < layout.top() || event.y() >= layout.bottom()) {
			onClose();
			return true;
		}
		return false;
	}

	/** Closes the window and the overlay and runs {@code /vcs place} for {@code version}, as if typed. */
	private void place(BuildSummary.Version version) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft != null) {
			minecraft.setScreen(null);
		}
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand(placeCommand(build, version));
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
	private Layout layout() {
		int cell = ClientConfig.settings().cellSize();
		int maxColumns = Math.max(1, (width - 2 * MARGIN - GAP) / (cell + GAP));
		int columns = Mth.clamp(versions.size(), 1, maxColumns);
		int rows = (versions.size() + columns - 1) / columns;
		int panelWidth = Math.min(Math.max(MIN_WIDTH, Math.max(font.width(title) + 2 * GAP, columns * (cell + GAP) + GAP)), width - 2 * MARGIN);
		int gridHeight = Math.min(rows * (cell + LABEL + GAP) + GAP, height - 2 * MARGIN - HEADER - FOOTER);
		int panelHeight = HEADER + gridHeight + FOOTER;
		int left = (width - panelWidth) / 2;
		int gridWidth = columns * cell + (columns - 1) * GAP;
		return new Layout(left, (height - panelHeight) / 2, panelWidth, panelHeight, cell, columns, rows, left + (panelWidth - gridWidth) / 2);
	}

	/**
	 * Where the window is, {@code width} by {@code height} pixels with its top left corner at {@code (left, top)}, and
	 * its grid of {@code columns} cells across, starting {@code gridLeft} pixels in.
	 */
	private record Layout(int left, int top, int width, int height, int cell, int columns, int rows, int gridLeft) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}

		int gridTop() {
			return top + HEADER;
		}

		int gridBottom() {
			return bottom() - FOOTER;
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
