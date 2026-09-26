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

import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.network.BuildSummary;

/**
 * The builds overlay: every build in the world in a grid, each cell showing the newest committed version of its build
 * turning slowly about its vertical axis, with the build's name, version and tags under it. The build of the selected
 * placement is outlined.
 * <p>
 * Clicking a cell closes the overlay and runs {@code /vcs place} for that version, so the copy shows up where the
 * player stands, ready to be lined up. A build bigger than {@link ClientConfig.Settings#autoDownloadLimit} shows a
 * placeholder instead of its preview until clicked, and that first click downloads the preview rather than placing.
 * <p>
 * The list is asked for when the overlay opens and when its refresh button is pressed, and not otherwise, see
 * {@link BuildBrowser}. How big the cells are and how fast they turn come from {@link ClientConfig}. The game keeps
 * running behind it.
 */
public final class BuildBrowserScreen extends Screen {
	/** Height of the strip at the top holding the title and the refresh button. */
	static final int HEADER = 32;
	/** Space between cells, and between the grid and the edges of the screen. */
	static final int GAP = 8;
	/** Height of the text under each preview: the name, then the version and its tags. */
	static final int LABEL = 24;
	/** Where the previews start turning from, in degrees: a corner towards the viewer, so two faces show. */
	static final float START_YAW = 45.0f;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF6B6B;
	private static final int CELL = 0x80000000;
	private static final int CELL_HOVERED = 0xA0303040;
	private static final int BORDER = 0xFF3A3A3A;
	private static final int BORDER_HOVERED = 0xFFB0B0B0;
	/** The yellow the selected placement's own box is drawn in. */
	private static final int BORDER_SELECTED = 0xFFFFFF55;

	private double scroll;

	public BuildBrowserScreen() {
		super(Component.literal("MCVCS Builds"));
	}

	@Override
	public void added() {
		BuildBrowser.refresh();
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> BuildBrowser.refresh())
			.bounds(width - GAP - 60, 6, 60, 20)
			.build());
		scroll = Mth.clamp(scroll, 0.0, maxScroll());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 12, WHITE);

		switch (BuildBrowser.listState()) {
			case NONE, LOADING -> loading(graphics, width / 2, height / 2, "Loading builds");
			case DENIED -> graphics.centeredText(font, "You need operator permission (cheats) to browse builds", width / 2, height / 2, RED);
			case LOADED -> {
				if (BuildBrowser.builds().isEmpty()) {
					graphics.centeredText(font, "No builds in this world yet; run /vcs create <buildname> to start one", width / 2, height / 2, GREY);
				} else {
					grid(graphics, mouseX, mouseY);
				}
			}
		}
	}

	private void grid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<BuildSummary> builds = BuildBrowser.builds();
		Layout layout = layout();
		ClientPlacement selected = ClientPlacements.selected();
		float yaw = yaw();
		@Nullable BuildSummary hovered = at(mouseX, mouseY);

		graphics.enableScissor(0, HEADER, width, height - GAP);
		for (int i = 0; i < builds.size(); i++) {
			int x = layout.x(i);
			int y = layout.y(i) - (int) scroll;
			if (y + layout.cellHeight() < HEADER || y > height - GAP) {
				continue;
			}
			BuildSummary build = builds.get(i);
			boolean isHovered = build == hovered;
			boolean isSelected = selected != null && selected.build().equals(build.name());
			cell(graphics, build, x, y, layout.cell(), isHovered, isSelected, yaw);
		}
		graphics.disableScissor();

		if (hovered != null) {
			graphics.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
		}
	}

	private void cell(GuiGraphicsExtractor graphics, BuildSummary build, int x, int y, int size, boolean hovered, boolean selected, float yaw) {
		int bottom = y + size + LABEL;
		graphics.fill(x, y, x + size, bottom, hovered ? CELL_HOVERED : CELL);
		graphics.outline(x - 1, y - 1, size + 2, size + LABEL + 2, selected ? BORDER_SELECTED : hovered ? BORDER_HOVERED : BORDER);

		int centerX = x + size / 2;
		int centerY = y + size / 2;
		Thumbnail thumbnail = BuildBrowser.thumbnail(build);
		if (thumbnail == null) {
			if (BuildBrowser.downloadsByItself(build)) {
				loading(graphics, centerX, centerY, "Waiting");
			} else {
				placeholder(graphics, build, x, y, size);
			}
		} else {
			switch (thumbnail.state()) {
				case REQUESTED -> loading(graphics, centerX, centerY, "Waiting");
				case DOWNLOADING -> loading(graphics, centerX, centerY, "Downloading " + (int) (thumbnail.progress() * 100) + "%");
				case MESHING -> loading(graphics, centerX, centerY, "Preparing");
				case FAILED -> wrapped(graphics, thumbnail.failure() + "; refresh to try again", x, y, size, RED);
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

		graphics.text(font, fit(build.name(), size - 6), x + 3, y + size + 2, WHITE);
		graphics.text(font, fit(build.versionLabel(), size - 6), x + 3, y + size + 2 + font.lineHeight + 1, GREY);
	}

	/** A build too big to download by itself: says so, and how big it is. */
	private void placeholder(GuiGraphicsExtractor graphics, BuildSummary build, int x, int y, int size) {
		wrapped(graphics, "Click to download the preview (" + String.format("%,d", build.extent().volume()) + " blocks)", x, y, size, GREY);
	}

	/** {@code text} wrapped to the cell's width and centred in its preview area. */
	private void wrapped(GuiGraphicsExtractor graphics, String text, int x, int y, int size, int color) {
		var lines = font.split(Component.literal(text), size - 8);
		int top = y + (size - lines.size() * font.lineHeight) / 2;
		for (int i = 0; i < lines.size(); i++) {
			graphics.centeredText(font, lines.get(i), x + size / 2, top + i * font.lineHeight, color);
		}
	}

	/** The game's own loading dots with {@code text} under them, centred on {@code (x, y)}. */
	private void loading(GuiGraphicsExtractor graphics, int x, int y, String text) {
		graphics.centeredText(font, LoadingDotsText.get(Util.getMillis()), x, y - font.lineHeight, GREY);
		graphics.centeredText(font, text, x, y + 2, GREY);
	}

	/** {@code text} cut down to {@code width} pixels, ending in an ellipsis if anything was cut. */
	private String fit(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, width - font.width("...")) + "...";
	}

	private static List<Component> tooltip(BuildSummary build) {
		String size = build.extent().sizeX() + "x" + build.extent().sizeY() + "x" + build.extent().sizeZ()
			+ " (" + String.format("%,d", build.extent().volume()) + " blocks)";
		String action = BuildBrowser.thumbnail(build) == null && !BuildBrowser.downloadsByItself(build)
			? "Click to download the preview"
			: "Click to place a copy where you stand";
		return List.of(Component.literal(build.name() + " " + build.versionLabel()), Component.literal(size), Component.literal(action));
	}

	/** How far the previews have turned by now: the same for every cell, so they turn together. */
	private static float yaw() {
		double speed = ClientConfig.settings().rotationSpeed();
		return (float) ((START_YAW + Util.getMillis() / 1000.0 * speed) % 360.0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		BuildSummary build = event.button() == 0 ? at(event.x(), event.y()) : null;
		if (build == null || minecraft == null) {
			return false;
		}
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		if (BuildBrowser.thumbnail(build) == null && !BuildBrowser.downloadsByItself(build)) {
			BuildBrowser.download(build);
		} else {
			place(build);
		}
		return true;
	}

	/** Closes the overlay and runs {@code /vcs place} for the version the cell shows, as if typed. */
	private void place(BuildSummary build) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		onClose();
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand("vcs place " + build.name() + " " + build.version());
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * (layout().cellHeight() + GAP) / 2.0, 0.0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BuildBrowserKey.KEY.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	/** The build whose cell is under {@code (x, y)}, or null. */
	private @Nullable BuildSummary at(double x, double y) {
		if (BuildBrowser.listState() != BuildBrowser.ListState.LOADED || y < HEADER || y >= height - GAP) {
			return null;
		}
		List<BuildSummary> builds = BuildBrowser.builds();
		Layout layout = layout();
		for (int i = 0; i < builds.size(); i++) {
			int cellX = layout.x(i);
			int cellY = layout.y(i) - (int) scroll;
			if (x >= cellX && x < cellX + layout.cell() && y >= cellY && y < cellY + layout.cellHeight()) {
				return builds.get(i);
			}
		}
		return null;
	}

	private double maxScroll() {
		Layout layout = layout();
		int rows = (BuildBrowser.builds().size() + layout.columns() - 1) / layout.columns();
		int content = rows * (layout.cellHeight() + GAP) + GAP;
		return Math.max(0, content - (height - HEADER - GAP));
	}

	private Layout layout() {
		int cell = ClientConfig.settings().cellSize();
		int columns = Math.max(1, (width - GAP) / (cell + GAP));
		int gridWidth = columns * cell + (columns - 1) * GAP;
		return new Layout(cell, columns, (width - gridWidth) / 2);
	}

	/**
	 * Where the cells go: {@code columns} of them across, each {@code cell} pixels wide with its label under it, the
	 * grid centred on the screen and starting {@code left} pixels in.
	 */
	private record Layout(int cell, int columns, int left) {
		int cellHeight() {
			return cell + LABEL;
		}

		int x(int index) {
			return left + (index % columns) * (cell + GAP);
		}

		/** Top of the cell before scrolling. */
		int y(int index) {
			return HEADER + GAP + (index / columns) * (cellHeight() + GAP);
		}
	}

	/** The cell of the build called {@code name} as it is laid out now, for tests to click; null if there is none. */
	public @Nullable ScreenRectangle cellOf(String name) {
		List<BuildSummary> builds = BuildBrowser.builds();
		Layout layout = layout();
		for (int i = 0; i < builds.size(); i++) {
			if (builds.get(i).name().equals(name)) {
				return new ScreenRectangle(layout.x(i), layout.y(i) - (int) scroll, layout.cell(), layout.cellHeight());
			}
		}
		return null;
	}
}
