package tony.mcvcs.client.browser;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.Build;
import tony.mcvcs.network.BuildSummary;

/**
 * Renames a build, for the {@code Rename} item of a cell's menu in the builds overlay: a window over the overlay with
 * the build's name in a field to edit. {@code Save}, or Enter in the field, runs {@code /vcs rename} as if typed and
 * goes back to the overlay, whose list is asked for again once the build is renamed. It can only be pressed once the
 * field holds a name a new build may have, other than the build's own. {@code Cancel}, Escape or a click outside the
 * window goes back to the overlay; the overlay's key closes both.
 */
public final class RenameBuildScreen extends Screen {
	/** Labels of the window's buttons. */
	public static final String SAVE = "Save";
	public static final String CANCEL = "Cancel";

	private static final int WIDTH = 240;
	private static final int PADDING = 8;
	private static final int BUTTON_WIDTH = 80;
	private static final int BUTTON_HEIGHT = 20;
	private static final int MARGIN = 16;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int BORDER = 0xFFB0B0B0;

	private final BuildBrowserScreen parent;
	private final BuildSummary build;
	/** What the field holds, kept across a resize of the window. */
	private String name;
	private @Nullable EditBox nameField;
	private @Nullable Button save;

	public RenameBuildScreen(BuildBrowserScreen parent, BuildSummary build) {
		super(Component.literal("Rename " + build.name()));
		this.parent = parent;
		this.build = build;
		this.name = build.name();
	}

	@Override
	protected void init() {
		// The overlay is drawn behind the window, so it has to be laid out for the screen as it is now.
		parent.resize(width, height);
		Layout layout = layout();

		EditBox field = new EditBox(font, layout.left() + PADDING, layout.fieldTop(), layout.width() - 2 * PADDING, BUTTON_HEIGHT, title);
		field.setMaxLength(64);
		field.setValue(name);
		field.setResponder(value -> {
			name = value;
			updateSave();
		});
		addRenderableWidget(field);
		nameField = field;

		int buttonsLeft = width / 2 - BUTTON_WIDTH - PADDING / 2;
		save = addRenderableWidget(Button.builder(Component.literal(SAVE), button -> save())
			.bounds(buttonsLeft, layout.buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(buttonsLeft + BUTTON_WIDTH + PADDING, layout.buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		updateSave();
		setInitialFocus(field);
	}

	/** Whether the field holds a name the build may be renamed to: one a new build may have, and not its own. */
	private boolean canSave() {
		return Build.isValidNewName(name) && !name.equals(build.name());
	}

	/** Lets {@code Save} be pressed only for a name the build may be renamed to, and says why not otherwise. */
	private void updateSave() {
		if (save == null) {
			return;
		}
		save.active = canSave();
		save.setTooltip(save.active || name.isEmpty() || name.equals(build.name()) ? null : Tooltip.create(Component.literal(
			"A build name must start with a letter from a to z and may only contain letters, digits, _ + - and dots between them")));
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
		graphics.centeredText(font, title, width / 2, layout.top() + PADDING, WHITE);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * Goes back to the overlay and runs {@code /vcs rename} as if typed, so the server checks the permission and the
	 * name and answers in chat the way it does for the command. The list is asked for again once it is done.
	 */
	private void save() {
		if (!canSave() || minecraft == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		onClose();
		if (player != null) {
			player.connection.sendCommand("vcs rename " + build.name() + " " + name);
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
		if (event.isConfirmation() && nameField != null && nameField.isFocused()) {
			save();
			return true;
		}
		// Typed into the name field, the overlay's key is just a letter.
		if (BuildBrowserKey.KEY.matches(event) && (nameField == null || !nameField.isFocused()) && minecraft != null) {
			minecraft.setScreen(null);
			return true;
		}
		return super.keyPressed(event);
	}

	/** The button labelled {@code label} as it is laid out now, for tests to click; null if there is none. */
	public @Nullable ScreenRectangle widgetOf(String label) {
		return children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().equals(label))
			.map(child -> ((AbstractWidget) child).getRectangle())
			.findFirst().orElse(null);
	}

	/** Whether {@code Save} can be pressed, for tests. */
	public boolean canPressSave() {
		return save != null && save.active;
	}

	private Layout layout() {
		int panelWidth = Math.min(WIDTH, width - 2 * MARGIN);
		int panelHeight = PADDING + font.lineHeight + PADDING + BUTTON_HEIGHT + PADDING + BUTTON_HEIGHT + PADDING;
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		int fieldTop = top + PADDING + font.lineHeight + PADDING;
		return new Layout(left, top, panelWidth, panelHeight, fieldTop, fieldTop + BUTTON_HEIGHT + PADDING);
	}

	private record Layout(int left, int top, int width, int height, int fieldTop, int buttonsTop) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}
	}
}
