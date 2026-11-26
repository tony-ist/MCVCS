package tony.mcvcs.client.browser;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
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
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.command.VcsCommandCreate;

/**
 * Starts a new build, for the {@code Create} button of the builds overlay: a window over the overlay asking for the
 * build's name, with a checkbox, unticked to begin with, to punch two corners of a box instead of one block of the
 * build. {@code Create}, or Enter in the name field, closes the overlay and runs {@code /vcs create} for the name, with
 * {@code -b} if the box is ticked, as if typed, so the player can go punch. It can only be pressed once the name is one
 * a new build may have. The name and checkbox are kept for next time until the build is created. {@code Cancel}, Escape or a click outside the window goes back to the overlay; the overlay's
 * key closes both.
 */
public final class CreateBuildScreen extends Screen {
	/** Labels of the window's buttons and checkbox. */
	public static final String CREATE = "Create";
	public static final String CANCEL = "Cancel";
	public static final String BOX = "Use box selection";

	private static final int WIDTH = 240;
	private static final int PADDING = 8;
	private static final int BUTTON_WIDTH = 80;
	private static final int BUTTON_HEIGHT = 20;
	private static final int MARGIN = 16;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF0101018;
	private static final int BORDER = 0xFFB0B0B0;

	/**
	 * What the window was last left with, so a build that was not created, say for being too big or overlapping a
	 * placement, can be tried again without typing it all again. Cleared once the build shows up, see {@link #register}.
	 */
	private static String name = "";
	private static boolean box;
	/** The name last sent with {@code /vcs create}, while it is waited for to show up among the placements; null otherwise. */
	private static @Nullable String submitted;

	private final BuildBrowserScreen parent;
	private @Nullable EditBox nameField;
	private @Nullable Button create;

	public CreateBuildScreen(BuildBrowserScreen parent) {
		super(Component.literal("Name of the new build"));
		this.parent = parent;
	}

	/** Clears the remembered name and checkbox once the server sends a placement of the build last asked for. */
	public static void register() {
		ClientPlacements.onUpdate(() -> {
			if (submitted != null && hasPlacement(submitted)) {
				submitted = null;
				name = "";
				box = false;
			}
		});
	}

	private static boolean hasPlacement(String build) {
		return ClientPlacements.all().stream().anyMatch(placement -> placement.build().equalsIgnoreCase(build));
	}

	@Override
	protected void init() {
		parent.resize(width, height);
		Layout layout = layout();
		int innerWidth = layout.width() - 2 * PADDING;

		EditBox field = new EditBox(font, layout.left() + PADDING, layout.fieldTop(), innerWidth, BUTTON_HEIGHT, title);
		field.setMaxLength(64);
		field.setValue(name);
		field.setResponder(value -> {
			name = value;
			updateCreate();
		});
		addRenderableWidget(field);
		nameField = field;

		Checkbox checkbox = Checkbox.builder(Component.literal(BOX), font)
			.pos(layout.left() + PADDING, layout.checkboxTop())
			.maxWidth(innerWidth)
			.selected(box)
			.onValueChange((checked, value) -> box = value)
			.build();
		checkbox.setTooltip(Tooltip.create(Component.literal(
			"Punch two opposite corners; the box between them becomes the build. Without this, punch one block and everything connected to it becomes the build")));
		addRenderableWidget(checkbox);

		int buttonsLeft = width / 2 - BUTTON_WIDTH - PADDING / 2;
		create = addRenderableWidget(Button.builder(Component.literal(CREATE), button -> create())
			.bounds(buttonsLeft, layout.buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(buttonsLeft + BUTTON_WIDTH + PADDING, layout.buttonsTop(), BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		updateCreate();
		setInitialFocus(field);
	}

	/** Lets {@code Create} be pressed only for a name a new build may have, and says why not otherwise. */
	private void updateCreate() {
		if (create == null) {
			return;
		}
		create.active = Build.isValidNewName(name);
		create.setTooltip(create.active || name.isEmpty() ? null : Tooltip.create(Component.literal(
			"A build name must start with a letter from a to z and may only contain letters, digits, _ + - and dots between them")));
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
		Layout layout = layout();
		graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), PANEL);
		graphics.outline(layout.left() - 1, layout.top() - 1, layout.width() + 2, layout.height() + 2, BORDER);
		graphics.centeredText(font, title, width / 2, layout.top() + PADDING, WHITE);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * Closes the overlay, so the player can punch, and runs {@code /vcs create} as if typed, so the server checks the
	 * permission and the name and answers in chat the way it does for the command.
	 */
	private void create() {
		if (!Build.isValidNewName(name) || minecraft == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		minecraft.setScreen(null);
		if (player != null) {
			// A build of that name already placed would clear the draft on the next sync whether or not this one is created.
			submitted = hasPlacement(name) ? null : name;
			player.connection.sendCommand("vcs create " + name + (box ? " " + VcsCommandCreate.BOX : ""));
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

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation() && nameField != null && nameField.isFocused()) {
			create();
			return true;
		}
		// Typed into the name field, the overlay's key is just a letter.
		if (BuildBrowserKey.KEY.matches(event) && (nameField == null || !nameField.isFocused()) && minecraft != null) {
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

	/** Whether {@code Create} can be pressed, for tests. */
	public boolean canCreate() {
		return create != null && create.active;
	}

	private Layout layout() {
		int panelWidth = Math.min(WIDTH, width - 2 * MARGIN);
		int checkbox = Checkbox.getBoxSize(font);
		int panelHeight = PADDING + font.lineHeight + PADDING + BUTTON_HEIGHT + PADDING + checkbox + PADDING + BUTTON_HEIGHT + PADDING;
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		int fieldTop = top + PADDING + font.lineHeight + PADDING;
		int checkboxTop = fieldTop + BUTTON_HEIGHT + PADDING;
		return new Layout(left, top, panelWidth, panelHeight, fieldTop, checkboxTop, checkboxTop + checkbox + PADDING);
	}

	private record Layout(int left, int top, int width, int height, int fieldTop, int checkboxTop, int buttonsTop) {
		int right() {
			return left + width;
		}

		int bottom() {
			return top + height;
		}
	}
}
