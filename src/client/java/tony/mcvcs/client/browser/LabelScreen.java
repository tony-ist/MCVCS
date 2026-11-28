package tony.mcvcs.client.browser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.Build;
import tony.mcvcs.network.BuildSummary;

/**
 * The {@code Label} item of a cell's menu in the builds overlay: every version of the build, see
 * {@link VersionGridScreen}, and below them a field for the label. Clicking a version picks it; {@code Save label}, or
 * Enter in the field, runs {@code /vcs label <version> <label> <buildname>} as if typed and goes back to the overlay,
 * whose list is asked for again once it is done. It can only be pressed once a version is picked and the field holds a
 * label the build does not have yet. {@code Cancel} goes back to the overlay.
 */
public final class LabelScreen extends VersionGridScreen {
	/** Labels of the window's buttons. */
	public static final String SAVE = "Save label";
	public static final String CANCEL = "Cancel";

	private static final int PADDING = 6;
	private static final int ROW = 20;
	private static final int BUTTON_WIDTH = 80;
	private static final int FIELD_MAX = 64;

	private BuildSummary.@Nullable Version chosen;
	/** What the field holds, kept across a resize of the window. */
	private String label = "";
	private @Nullable EditBox field;
	private @Nullable Button save;

	public LabelScreen(BuildBrowserScreen parent, BuildSummary build) {
		super(Component.literal("Label a version of " + build.name()), parent, build);
		// With only one version there is nothing to pick.
		if (versions.size() == 1) {
			chosen = versions.getFirst();
		}
	}

	@Override
	protected int headerHeight() {
		return PADDING + font.lineHeight + 2 + font.lineHeight + PADDING;
	}

	@Override
	protected int footerHeight() {
		return PADDING + ROW + PADDING / 2 + ROW + PADDING;
	}

	@Override
	protected void extractHeader(GuiGraphicsExtractor graphics, Layout layout, int top) {
		int y = top + PADDING;
		graphics.centeredText(font, fit(title.getString(), layout.width() - 2 * PADDING), width / 2, y, WHITE);
		graphics.centeredText(font, fit("Pick a version, then type its label", layout.width() - 2 * PADDING), width / 2, y + font.lineHeight + 2, GREY);
	}

	@Override
	protected void initFooter(Layout layout) {
		int row1 = layout.gridBottom() + PADDING;
		int row2 = row1 + ROW + PADDING / 2;
		EditBox box = new EditBox(font, layout.left() + PADDING, row1, layout.width() - 2 * PADDING, ROW, Component.literal("Label"));
		box.setMaxLength(FIELD_MAX);
		box.setHint(Component.literal("Label, e.g. with-roof").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(label);
		box.setResponder(value -> {
			label = value;
			updateSave();
		});
		addRenderableWidget(box);
		field = box;

		int left = width / 2 - BUTTON_WIDTH - PADDING / 2;
		save = addRenderableWidget(Button.builder(Component.literal(SAVE), button -> save())
			.bounds(left, row2, BUTTON_WIDTH, ROW)
			.build());
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(left + BUTTON_WIDTH + PADDING, row2, BUTTON_WIDTH, ROW)
			.build());
		updateSave();
		setInitialFocus(box);
	}

	/** Why the label cannot be saved as it stands, or null when it can. */
	private @Nullable String refusal() {
		if (chosen == null) {
			return "Pick a version";
		}
		if (label.isEmpty()) {
			return "Type a label";
		}
		if (!Build.isValidTag(label)) {
			return "Use letters, digits, - _ + and dots, but not only digits";
		}
		for (BuildSummary.Version version : versions) {
			if (version.tags().contains(label)) {
				return "v" + version.version() + " already has this label";
			}
		}
		return null;
	}

	/** Lets {@code Save label} be pressed only when it can go ahead, and says why not otherwise. */
	private void updateSave() {
		if (save == null) {
			return;
		}
		String refusal = refusal();
		save.active = refusal == null;
		save.setTooltip(Tooltip.create(Component.literal(refusal == null ? "Label v" + chosen.version() + " as " + label : refusal)));
	}

	@Override
	protected boolean highlighted(BuildSummary.Version version) {
		return version == chosen;
	}

	@Override
	protected Component tooltip(BuildSummary.Version version) {
		return Component.literal("Label " + version.label());
	}

	@Override
	protected void clicked(BuildSummary.Version version) {
		chosen = version;
		updateSave();
		if (field != null) {
			setFocused(field);
		}
	}

	/**
	 * Goes back to the overlay and runs {@code /vcs label} as if typed, so the server checks the permission and the label
	 * and answers in chat the way it does for the command. The list is asked for again once it is done.
	 */
	private void save() {
		BuildSummary.Version version = chosen;
		if (version == null || refusal() != null || minecraft == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		onClose();
		if (player != null) {
			player.connection.sendCommand("vcs label " + version.version() + " " + label + " " + build.name());
			BuildBrowser.refreshAfterCommand();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = field != null && field.isFocused();
		if (event.isConfirmation() && typing) {
			save();
			return true;
		}
		// Typed into the field, the overlay's key is just a letter.
		if (typing && BuildBrowserKey.KEY.matches(event)) {
			return field.keyPressed(event);
		}
		return super.keyPressed(event);
	}

	/** Whether {@code Save label} can be pressed, for tests. */
	public boolean canPressSave() {
		return save != null && save.active;
	}

	/** The button labelled {@code label} as it is laid out now, for tests to click; null if there is none. */
	public @Nullable ScreenRectangle widgetOf(String label) {
		return children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().equals(label))
			.map(child -> ((AbstractWidget) child).getRectangle())
			.findFirst().orElse(null);
	}
}
