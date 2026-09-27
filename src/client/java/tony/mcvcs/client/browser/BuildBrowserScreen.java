package tony.mcvcs.client.browser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
 * Hovering a cell swaps its preview for three buttons, each closing the overlay and running a command as if typed:
 * {@code Select} runs {@code /vcs select} for the build, {@code TP} runs {@code /vcs tp} to it, and {@code Place} runs
 * {@code /vcs place} for the version the cell shows, so the copy shows up where the player stands, ready to be lined
 * up. A build with no placements has the first two greyed out; for one with several, they first ask which placement
 * is meant, see {@link PlacementPickerScreen}. Three dots in the hovered cell's top right corner open a menu whose
 * {@code Delete} asks whether to delete the build, see {@link DeleteBuildScreen}. A build bigger than
 * {@link ClientConfig.Settings#autoDownloadLimit} shows a placeholder instead of its preview until the cell is clicked
 * outside its buttons, which downloads the preview.
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
	private static final int CELL_HOVERED = 0xD0304A6A;
	private static final int BORDER = 0xFF3A3A3A;
	private static final int BORDER_HOVERED = 0xFFB0B0B0;
	/** The yellow the selected placement's own box is drawn in. */
	private static final int BORDER_SELECTED = 0xFFFFFF55;

	/** Labels of the buttons a hovered cell shows. */
	public static final String SELECT = "Select";
	public static final String TP = "TP";
	public static final String PLACE = "Place";
	/** Height of those buttons, unless the cell is too small for three of them stacked. */
	private static final int BUTTON_HEIGHT = 20;
	/** Space between the buttons, and between them and the edges of the preview. */
	private static final int BUTTON_GAP = 2;
	/** Width a button needs beyond its label. */
	private static final int BUTTON_PADDING = 16;

	/** Label of the three dots in a hovered cell's top right corner, which open its menu, and of that menu's one item. */
	public static final String MORE = "More";
	public static final String DELETE = "Delete";
	/** Size of the three dots' button, and the space between it and the corner of the cell. */
	private static final int MORE_WIDTH = 14;
	private static final int MORE_HEIGHT = 10;
	private static final int MORE_INSET = 2;
	private static final int MORE_BACKGROUND = 0x80000000;
	private static final int MORE_HOVERED = 0xFF5A5A5A;
	/** Space between the menu's edges and its items. */
	private static final int MENU_PADDING = 2;
	private static final int MENU = 0xF0101018;

	private double scroll;
	/** The build whose cell the buttons are on, or null while no cell is hovered and they are hidden. */
	private @Nullable BuildSummary buttonsFor;
	/** The build whose menu is open, or null while it is closed; its cell keeps its buttons wherever the mouse goes. */
	private @Nullable BuildSummary menuFor;
	private final Button select = Button.builder(Component.literal(SELECT), button -> choose(PlacementPickerScreen.Action.SELECT)).build();
	private final Button tp = Button.builder(Component.literal(TP), button -> choose(PlacementPickerScreen.Action.TP)).build();
	private final Button place = Button.builder(Component.literal(PLACE), button -> run(build -> "vcs place " + build.name() + " " + build.version())).build();
	private final Button more = new DotsButton(button -> menuFor = buttonsFor);
	/** The menu's item; clicked by {@link #mouseClicked} itself, since it lies over the cell's other buttons. */
	private final Button delete = Button.builder(Component.literal(DELETE), button -> confirmDelete()).build();

	public BuildBrowserScreen() {
		super(Component.literal("MCVCS Builds"));
	}

	/** Whether the list has been asked for since this overlay opened; coming back to it from the picker keeps it. */
	private boolean listed;

	@Override
	public void added() {
		if (!listed) {
			listed = true;
			BuildBrowser.refresh();
		}
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> BuildBrowser.refresh())
			.bounds(width - GAP - 60, 6, 60, 20)
			.build());
		// Clickable but not drawn with the other widgets: they go over the previews, so the grid draws them after its cells.
		for (Button button : buttons()) {
			addWidget(button);
		}
		addWidget(more);
		buttonsFor = null;
		menuFor = null;
		hideButtons();
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
					grid(graphics, mouseX, mouseY, partialTick);
				}
			}
		}
	}

	private void grid(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		List<BuildSummary> builds = BuildBrowser.builds();
		Layout layout = layout();
		ClientPlacement selected = ClientPlacements.selected();
		float yaw = yaw();
		@Nullable BuildSummary hovered = updateButtons(mouseX, mouseY);

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
		if (hovered != null) {
			// Under an open menu the cell's buttons neither light up nor show their tooltips.
			int buttonMouseX = menuFor == null ? mouseX : -1;
			int buttonMouseY = menuFor == null ? mouseY : -1;
			for (Button button : buttons()) {
				button.extractRenderState(graphics, buttonMouseX, buttonMouseY, partialTick);
			}
			more.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
		graphics.disableScissor();

		if (menuFor != null) {
			menu(graphics, mouseX, mouseY, partialTick);
		} else if (hovered != null && !overButton(mouseX, mouseY)) {
			// Over a button, that button's own tooltip says what it does instead.
			graphics.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
		}
	}

	/** The open menu, hanging from the three dots and lined up with their right edge, over everything else. */
	private void menu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int itemWidth = font.width(delete.getMessage()) + BUTTON_PADDING;
		int right = more.getRight();
		int top = more.getBottom() + 1;
		int left = right - itemWidth - 2 * MENU_PADDING;
		int bottom = top + BUTTON_HEIGHT + 2 * MENU_PADDING;
		delete.visible = true;
		delete.setRectangle(itemWidth, BUTTON_HEIGHT, left + MENU_PADDING, top + MENU_PADDING);

		graphics.nextStratum();
		graphics.fill(left, top, right, bottom, MENU);
		graphics.outline(left - 1, top - 1, right - left + 2, bottom - top + 2, BORDER_HOVERED);
		delete.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void closeMenu() {
		menuFor = null;
		delete.visible = false;
	}

	/** Asks whether to delete the build whose menu is open, see {@link DeleteBuildScreen}. */
	private void confirmDelete() {
		BuildSummary build = menuFor;
		closeMenu();
		if (build != null && minecraft != null) {
			minecraft.setScreen(new DeleteBuildScreen(this, build));
		}
	}

	private void cell(GuiGraphicsExtractor graphics, BuildSummary build, int x, int y, int size, boolean hovered, boolean selected, float yaw) {
		int bottom = y + size + LABEL;
		graphics.fill(x, y, x + size, bottom, hovered ? CELL_HOVERED : CELL);
		graphics.outline(x - 1, y - 1, size + 2, size + LABEL + 2, selected ? BORDER_SELECTED : hovered ? BORDER_HOVERED : BORDER);

		// A hovered cell has its buttons where the preview was, with nothing behind them.
		if (!hovered) {
			preview(graphics, build, x, y, size, yaw);
		}

		graphics.text(font, fit(build.name(), size - 6), x + 3, y + size + 2, WHITE);
		graphics.text(font, fit(build.versionLabel(), size - 6), x + 3, y + size + 2 + font.lineHeight + 1, GREY);
	}

	/** The preview of the version {@code build} names, or what is keeping it, on the square at the top of its cell. */
	private void preview(GuiGraphicsExtractor graphics, BuildSummary build, int x, int y, int size, float yaw) {
		int centerX = x + size / 2;
		int centerY = y + size / 2;
		Thumbnail thumbnail = BuildBrowser.thumbnail(build);
		if (thumbnail == null) {
			if (BuildBrowser.downloadsByItself(build)) {
				loading(graphics, centerX, centerY, "Downloading");
			} else {
				placeholder(graphics, build, x, y, size);
			}
		} else {
			switch (thumbnail.state()) {
				case REQUESTED -> loading(graphics, centerX, centerY, "Downloading");
				case DOWNLOADING -> loading(graphics, centerX, centerY, "Downloading " + (int) (thumbnail.progress() * 100) + "%");
				case MESHING -> loading(graphics, centerX, centerY, "Preparing");
				case FAILED -> wrapped(graphics, thumbnail.failure() + (thumbnail.retryable() ? "; refresh to try again" : ""), x, y, size, RED);
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
		String placements = switch (build.placements()) {
			case 0 -> "Not placed anywhere";
			case 1 -> "1 placement";
			default -> build.placements() + " placements";
		};
		List<Component> lines = new ArrayList<>(List.of(
			Component.literal(build.name() + " " + build.versionLabel()), Component.literal(size), Component.literal(placements)));
		if (waitsForClick(build)) {
			lines.add(Component.literal("Click to download the preview"));
		}
		return lines;
	}

	/** Whether {@code build}'s preview is not downloaded until its cell is clicked, and that click has not come yet. */
	private static boolean waitsForClick(BuildSummary build) {
		return BuildBrowser.thumbnail(build) == null && !BuildBrowser.downloadsByItself(build);
	}

	/** How far the previews have turned by now: the same for every cell, so they turn together. */
	private static float yaw() {
		double speed = ClientConfig.settings().rotationSpeed();
		return (float) ((START_YAW + Util.getMillis() / 1000.0 * speed) % 360.0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// An open menu takes the click: its item does its thing, and anywhere else, the dots included, closes it.
		if (menuFor != null) {
			if (!delete.mouseClicked(event, doubleClick)) {
				closeMenu();
			}
			return true;
		}
		// The buttons follow the mouse only when drawn, and the mouse may have moved since.
		updateButtons(event.x(), event.y());
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		if (overButton(event.x(), event.y())) {
			// A greyed-out button: it does nothing, and neither does the cell under it.
			return true;
		}
		BuildSummary build = at(event.x(), event.y());
		if (build == null || minecraft == null || !waitsForClick(build)) {
			return false;
		}
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		BuildBrowser.download(build);
		return true;
	}

	/**
	 * Runs {@code action} for the build the buttons are on: straight away if it has at most one placement, which the
	 * command then finds by itself or says there is none, otherwise once {@link PlacementPickerScreen} is told which.
	 */
	private void choose(PlacementPickerScreen.Action action) {
		BuildSummary build = buttonsFor;
		if (build == null || minecraft == null) {
			return;
		}
		List<ClientPlacement> placements = ClientPlacements.all().stream()
			.filter(placement -> placement.build().equals(build.name()))
			.sorted(Comparator.comparing(ClientPlacement::placement))
			.toList();
		if (placements.size() > 1) {
			minecraft.setScreen(new PlacementPickerScreen(this, build.name(), action, placements));
		} else {
			run(target -> action.command() + " " + target.name());
		}
	}

	/** Closes the overlay and runs the command {@code command} makes for the build the buttons are on, as if typed. */
	private void run(Function<BuildSummary, String> command) {
		BuildSummary build = buttonsFor;
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (build == null) {
			return;
		}
		onClose();
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand(command.apply(build));
		}
	}

	private List<Button> buttons() {
		return List.of(select, tp, place);
	}

	private void hideButtons() {
		for (Button button : buttons()) {
			button.visible = false;
		}
		more.visible = false;
		closeMenu();
	}

	/** Whether {@code (x, y)} is on one of the buttons, greyed out or not. */
	private boolean overButton(double x, double y) {
		return more.visible && more.isMouseOver(x, y) || buttons().stream().anyMatch(button -> button.visible && button.isMouseOver(x, y));
	}

	/**
	 * Puts the buttons on the cell under {@code (x, y)}, or while a menu is open on that menu's cell, or hides them if
	 * there is none, and says whose cell that is. What they say and whether they can be pressed is only worked out
	 * again when the mouse moves onto another build.
	 */
	private @Nullable BuildSummary updateButtons(double x, double y) {
		BuildSummary build = menuFor != null ? menuFor : at(x, y);
		ScreenRectangle cell = build == null ? null : cellOf(build.name());
		if (build == null || cell == null) {
			buttonsFor = null;
			hideButtons();
			return null;
		}
		if (!build.equals(buttonsFor)) {
			buttonsFor = build;
			boolean placed = build.placements() > 0;
			Tooltip notPlaced = Tooltip.create(Component.literal("Not placed anywhere; place it first"));
			select.active = placed;
			select.setTooltip(!placed ? notPlaced : Tooltip.create(Component.literal(build.placements() == 1
				? "Select its placement"
				: "Choose which of its " + build.placements() + " placements to select")));
			tp.active = placed;
			tp.setTooltip(!placed ? notPlaced : Tooltip.create(Component.literal(build.placements() == 1
				? "Teleport on top of its placement"
				: "Choose which of its " + build.placements() + " placements to teleport onto")));
			place.setTooltip(Tooltip.create(Component.literal("Place a copy of v" + build.version() + " where you stand")));
		}
		for (Button button : buttons()) {
			button.visible = true;
		}
		more.visible = true;
		layoutButtons(cell.left(), cell.top(), cell.width());
		more.setRectangle(MORE_WIDTH, MORE_HEIGHT, cell.right() - MORE_INSET - MORE_WIDTH, cell.top() + MORE_INSET);
		return build;
	}

	/**
	 * Stacks the buttons in the middle of the preview of the cell at {@code (x, y)}, all as wide as the widest label
	 * needs, and lower than {@link #BUTTON_HEIGHT} only if the preview is too small for three of them.
	 */
	private void layoutButtons(int x, int y, int size) {
		List<Button> buttons = buttons();
		int count = buttons.size();
		int widest = buttons.stream().mapToInt(button -> font.width(button.getMessage())).max().orElse(0);
		int buttonWidth = Math.min(widest + BUTTON_PADDING, size - 2 * BUTTON_GAP);
		int buttonHeight = Math.min(BUTTON_HEIGHT, (size - 2 * BUTTON_GAP - (count - 1) * BUTTON_GAP) / count);
		int left = x + (size - buttonWidth) / 2;
		int top = y + (size - count * buttonHeight - (count - 1) * BUTTON_GAP) / 2;
		for (int i = 0; i < count; i++) {
			buttons.get(i).setRectangle(buttonWidth, buttonHeight, left, top + i * (buttonHeight + BUTTON_GAP));
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		// The menu hangs from its cell, which is about to move.
		closeMenu();
		scroll = Mth.clamp(scroll - scrollY * (layout().cellHeight() + GAP) / 2.0, 0.0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BuildBrowserKey.KEY.matches(event)) {
			onClose();
			return true;
		}
		if (event.isEscape() && menuFor != null) {
			closeMenu();
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

	/**
	 * The button labelled {@code label} on the hovered cell or in its open menu, for tests to click; null while no cell
	 * is hovered, or for the menu's item while the menu is closed.
	 */
	public @Nullable Button buttonOf(String label) {
		return Stream.concat(buttons().stream(), Stream.of(more, delete))
			.filter(button -> button.visible && button.getMessage().getString().equals(label)).findFirst().orElse(null);
	}

	/** Three dots in a row, lighter behind them under the mouse, which open a cell's menu. */
	private static final class DotsButton extends Button {
		DotsButton(OnPress onPress) {
			super(0, 0, MORE_WIDTH, MORE_HEIGHT, Component.literal(MORE), onPress, DEFAULT_NARRATION);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? MORE_HOVERED : MORE_BACKGROUND);
			int left = getX() + (getWidth() - 8) / 2;
			int top = getY() + (getHeight() - 2) / 2;
			for (int i = 0; i < 3; i++) {
				graphics.fill(left + 3 * i, top, left + 3 * i + 2, top + 2, WHITE);
			}
		}
	}
}
