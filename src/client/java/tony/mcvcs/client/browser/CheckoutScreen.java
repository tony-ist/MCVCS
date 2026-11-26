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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import tony.mcvcs.build.ClientPlacement;
import tony.mcvcs.command.VcsCommand;
import tony.mcvcs.network.BuildSummary;
import tony.mcvcs.network.PlacementStatusPayload;

/**
 * The builds overlay's {@code Checkout} window, for the selected placement: its header names the build and the
 * placement and says which version the placement holds and how many of its blocks are modified, and the grid below
 * shows every version of the build, see {@link VersionGridScreen}, the one it holds marked {@code Current}. Clicking a
 * version picks it; {@code Check out} then closes the window and the overlay and runs {@code /vcs checkout} for it, with
 * {@code -f} if {@code Override all the modified blocks} is ticked, as if typed. While blocks are modified,
 * {@code Check out} is greyed out until that box is ticked, and {@code Show modified blocks} closes both and runs
 * {@code /vcs diff}. {@code Cancel} goes back to the overlay.
 */
public final class CheckoutScreen extends VersionGridScreen {
	/** Labels of the window's buttons and checkbox. */
	public static final String CHECKOUT = "Check out";
	public static final String CANCEL = "Cancel";
	public static final String OVERRIDE = "Override all the modified blocks";
	public static final String SHOW_MODIFIED = "Show modified blocks";
	/** What marks the version the placement holds. */
	public static final String CURRENT = "Current";

	private static final int PADDING = 6;
	private static final int ROW = 20;
	private static final int BUTTON_PADDING = 16;

	private final ClientPlacement placement;
	private BuildSummary.@Nullable Version chosen;
	private boolean override;
	private @Nullable Button checkout;
	private @Nullable Button showModified;

	public CheckoutScreen(BuildBrowserScreen parent, BuildSummary build, ClientPlacement placement) {
		super(Component.literal("Check out a version of " + placement.build() + " into placement " + placement.placement()), parent, build);
		this.placement = placement;
	}

	/** The command that checks out {@code version} into the selected placement, without the slash. */
	static String checkoutCommand(BuildSummary.Version version, boolean override) {
		return "vcs checkout " + version.version() + (override ? " " + VcsCommand.FORCE : "");
	}

	@Override
	public void added() {
		BuildBrowser.requestStatus();
	}

	/** How many blocks of the placement are modified, or {@link PlacementStatusPayload#UNKNOWN} while that is not known. */
	public int modified() {
		PlacementStatusPayload status = BuildBrowser.status();
		return status != null && status.placement().equals(placement.label()) ? status.modified() : PlacementStatusPayload.UNKNOWN;
	}

	private boolean statusArrived() {
		return BuildBrowser.status() != null;
	}

	@Override
	protected int minWidth() {
		// Room for the checkbox and Show modified blocks side by side, and for the heading on one line if the screen allows.
		int footer = Checkbox.getBoxSize(font) + 4 + font.width(OVERRIDE) + 3 * PADDING + font.width(SHOW_MODIFIED) + BUTTON_PADDING;
		return Math.max(footer, font.width(headingText()) + 2 * PADDING);
	}

	@Override
	protected int headerHeight() {
		return PADDING + headerLines(panelWidth() - 2 * PADDING).size() * (font.lineHeight + 2) + PADDING;
	}

	/** The heading, then the status, wrapped to {@code width}. */
	private List<FormattedCharSequence> headerLines(int width) {
		List<FormattedCharSequence> lines = new ArrayList<>(heading(width));
		lines.addAll(font.split(status(), width));
		return lines;
	}

	@Override
	protected int footerHeight() {
		return PADDING + ROW + PADDING / 2 + ROW + PADDING;
	}

	/** "Check out build <build> into placement <placement>", with both names in yellow. */
	private Component headingText() {
		return Component.literal("Check out build ")
			.append(Component.literal(placement.build()).withStyle(ChatFormatting.YELLOW))
			.append(" into placement ")
			.append(Component.literal(placement.placement()).withStyle(ChatFormatting.YELLOW));
	}

	/** {@link #headingText()} wrapped to {@code width}. */
	private List<FormattedCharSequence> heading(int width) {
		return font.split(headingText(), width);
	}

	/** Which version the placement holds and how many of its blocks are modified. */
	private Component status() {
		String holds = build.versions().stream().filter(version -> version.version() == placement.head())
			.findFirst().map(BuildSummary.Version::label).orElse("v" + placement.head());
		MutableComponent text = Component.literal("The placement holds " + holds + " · ").withStyle(ChatFormatting.GRAY);
		int modified = modified();
		if (!statusArrived()) {
			return text.append("checking for modified blocks...");
		}
		if (modified == PlacementStatusPayload.UNKNOWN) {
			return text.append(Component.literal("could not check for modified blocks").withStyle(ChatFormatting.RED));
		}
		if (modified == 0) {
			return text.append(Component.literal("no modified blocks").withStyle(ChatFormatting.GREEN));
		}
		return text.append(Component.literal(String.format("%,d", modified) + (modified == 1 ? " modified block" : " modified blocks")).withStyle(ChatFormatting.GOLD));
	}

	@Override
	protected void extractHeader(GuiGraphicsExtractor graphics, Layout layout, int top) {
		int y = top + PADDING;
		for (FormattedCharSequence line : headerLines(layout.width() - 2 * PADDING)) {
			graphics.centeredText(font, line, width / 2, y, WHITE);
			y += font.lineHeight + 2;
		}
	}

	@Override
	protected void initFooter(Layout layout) {
		int row1 = layout.gridBottom() + PADDING;
		int row2 = row1 + ROW + PADDING / 2;
		int boxSize = Checkbox.getBoxSize(font);
		Checkbox checkbox = Checkbox.builder(Component.literal(OVERRIDE), font)
			.pos(layout.left() + PADDING, row1 + (ROW - boxSize) / 2)
			.selected(override)
			.onValueChange((box, value) -> {
				override = value;
				updateButtons();
			})
			.build();
		checkbox.setTooltip(Tooltip.create(Component.literal("Checks out over the blocks changed since " + placement.label()
			+ " last held a version, and over anything standing where a bigger version would reach. Those blocks are lost for good.")));
		addRenderableWidget(checkbox);

		int showWidth = font.width(SHOW_MODIFIED) + BUTTON_PADDING;
		showModified = addRenderableWidget(Button.builder(Component.literal(SHOW_MODIFIED), button -> showModified())
			.bounds(layout.right() - PADDING - showWidth, row1, showWidth, ROW)
			.tooltip(Tooltip.create(Component.literal("Close the overlay and highlight the blocks changed since " + placement.label() + " last held a version")))
			.build());

		int checkoutWidth = font.width(CHECKOUT + " v" + build.version()) + BUTTON_PADDING;
		int cancelWidth = 80;
		int left = width / 2 - (checkoutWidth + PADDING + cancelWidth) / 2;
		checkout = addRenderableWidget(Button.builder(Component.literal(CHECKOUT), button -> checkout())
			.bounds(left, row2, checkoutWidth, ROW)
			.build());
		addRenderableWidget(Button.builder(Component.literal(CANCEL), button -> onClose())
			.bounds(left + checkoutWidth + PADDING, row2, cancelWidth, ROW)
			.build());
		updateButtons();
	}

	/** Shows {@code Show modified blocks} only while blocks are modified, and lets {@code Check out} be pressed only when it can go ahead. */
	private void updateButtons() {
		int modified = modified();
		if (showModified != null) {
			showModified.visible = modified > 0;
		}
		if (checkout == null) {
			return;
		}
		String label = chosen == null ? CHECKOUT : CHECKOUT + " v" + chosen.version();
		if (!checkout.getMessage().getString().equals(label)) {
			checkout.setMessage(Component.literal(label));
		}
		boolean blocked = modified > 0 && !override;
		checkout.active = chosen != null && !blocked;
		String tooltip = chosen == null ? "Pick a version above"
			: blocked ? placement.label() + " has modified blocks: commit them first, or tick \"" + OVERRIDE + "\" to lose them"
			: "Put " + chosen.label() + " into " + placement.label() + (override ? ", overriding the modified blocks" : "");
		checkout.setTooltip(Tooltip.create(Component.literal(tooltip)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// The count arrives after the window opens.
		updateButtons();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	protected boolean highlighted(BuildSummary.Version version) {
		return version == chosen;
	}

	@Override
	protected @Nullable String marker(BuildSummary.Version version) {
		return version.version() == placement.head() ? CURRENT : null;
	}

	@Override
	protected Component tooltip(BuildSummary.Version version) {
		return Component.literal(version.version() == placement.head()
			? placement.label() + " holds " + version.label() + "; pick it to put it back as it was committed"
			: "Pick " + version.label() + " to check out into " + placement.label());
	}

	@Override
	protected void clicked(BuildSummary.Version version) {
		chosen = version;
		updateButtons();
	}

	/** Closes the window and the overlay and runs {@code /vcs checkout} for the chosen version, as if typed. */
	private void checkout() {
		BuildSummary.Version version = chosen;
		if (version == null) {
			return;
		}
		closeAndRun(checkoutCommand(version, override));
	}

	/** Closes the window and the overlay and runs {@code /vcs diff}, which highlights the modified blocks. */
	private void showModified() {
		closeAndRun("vcs diff");
	}

	private void closeAndRun(String command) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft != null) {
			minecraft.setScreen(null);
		}
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand(command);
		}
	}

	/** The button or checkbox labelled {@code label}, or starting with it, as it is laid out now, for tests to click; null if there is none shown. */
	public @Nullable AbstractWidget widgetOf(String label) {
		return children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.visible && widget.getMessage().getString().startsWith(label))
			.map(AbstractWidget.class::cast)
			.findFirst().orElse(null);
	}

	/** Where {@link #widgetOf} is, for tests to click; null if it is not shown. */
	public @Nullable ScreenRectangle rectangleOf(String label) {
		AbstractWidget widget = widgetOf(label);
		return widget == null ? null : widget.getRectangle();
	}
}
