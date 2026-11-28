package tony.mcvcs.client.browser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import net.minecraft.ChatFormatting;
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
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.resize.ClientResize;
import tony.mcvcs.client.selection.PendingClickHighlight;
import tony.mcvcs.network.BuildSummary;
import tony.mcvcs.network.PlacementStatusPayload;

/**
 * The builds overlay: every build in the world in a grid, each cell showing the newest committed version of its build
 * turning slowly about its vertical axis, with the build's name, version and tags under it. The build of the selected
 * placement is outlined.
 * <p>
 * Hovering a cell swaps its preview for three buttons, each closing the overlay and running a command as if typed:
 * {@code Select} runs {@code /vcs select} for the build, {@code TP} runs {@code /vcs tp} to it, and {@code Place} runs
 * {@code /vcs place} for one of its versions, so the copy shows up where the player stands, ready to be lined up; a
 * build with several versions first asks which, see {@link VersionPickerScreen}. A build with no placements has the first two greyed out; for one with several, they first ask which placement
 * is meant, see {@link PlacementPickerScreen}. Three dots in the hovered cell's top right corner open a menu whose
 * {@code Rename} asks for a new name, see {@link RenameBuildScreen}, and whose {@code Delete} asks whether to delete
 * the build, see {@link DeleteBuildScreen}. A build bigger than
 * {@link ClientConfig.Settings#autoDownloadLimit} shows a placeholder instead of its preview until the cell is clicked
 * outside its buttons, which downloads the preview. Three dots in the top right corner open the header's menu, whose
 * {@code Deselect} runs {@code /vcs deselect}, whose {@code Resize} closes the overlay and runs {@code /vcs resize},
 * and whose {@code Unplace} asks whether to remove the selected placement from the world, see {@link UnplaceScreen};
 * while a resize goes on, a {@code Confirm resizing} button in the top left corner, right of {@code Cancel placing},
 * runs {@code /vcs confirmResize}, and a {@code Cancel resizing} button right of it runs {@code /vcs cancelResize}. The
 * {@code Create} button left of them starts a new build, see
 * {@link CreateBuildScreen}; while that build waits for its punch, a {@code Cancel new build} button in the top left
 * corner cancels it.
 * {@code Checkout} left of it checks out a version into the selected placement, see {@link CheckoutScreen}, and
 * {@code Commit} left of that previews and commits it, see {@link CommitScreen}. {@code Diff} left of that shows how
 * many blocks of the selected placement changed since the version it holds and runs {@code /vcs changes} to highlight
 * them in the world; while they are highlighted it reads {@code Hide diff} and runs {@code /vcs changes off}. While a
 * {@code /vcs place} copy is being lined up, a {@code Confirm placing} button in the top left corner runs
 * {@code /vcs confirmPlace}, and a {@code Cancel placing} button right of it, left of {@code Cancel new build}, runs
 * {@code /vcs cancelPlace}.
 * <p>
 * The list is asked for when the overlay opens, and not otherwise, see
 * {@link BuildBrowser}. How big the cells are and how fast they turn come from {@link ClientConfig}. The game keeps
 * running behind it.
 */
public final class BuildBrowserScreen extends Screen {
	/** Height of the strip at the top holding the title and the header's buttons. */
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

	/** Label of the header button in the top right corner, which asks for a new build's name, see {@link CreateBuildScreen}. */
	public static final String CREATE = "New build";
	/** Label of the header button right of {@link #CANCEL_PLACE}, shown only while a {@code /vcs create} waits for its punch. */
	public static final String CANCEL_CREATION = "Cancel new build";
	/** Label of the header button left of {@link #CREATE}, which checks out a version into the selected placement, see {@link CheckoutScreen}. */
	public static final String CHECKOUT = "Load";
	/** Label of the header button left of {@link #CHECKOUT}, which previews and commits the selected placement, see {@link CommitScreen}. */
	public static final String COMMIT = "Save";
	/**
	 * Label of the header button left of {@link #COMMIT}, followed by how many blocks of the selected placement changed,
	 * which runs {@code /vcs changes}; while a diff is highlighted it reads {@link #HIDE_DIFF} and runs {@code /vcs changes off}.
	 */
	public static final String DIFF = "Changes";
	public static final String HIDE_DIFF = "Hide changes";
	/** Label of the header button in the top left corner, shown only while a {@code /vcs place} copy is being lined up. */
	public static final String CANCEL_PLACE = "✖ Cancel placing";
	/** Label of the header button left of {@link #CANCEL_PLACE}, shown with it, which places the copy as {@code /vcs confirmPlace} does. */
	public static final String CONFIRM_PLACE = "✔ Confirm placing";
	/** Labels of the buttons a hovered cell shows. */
	public static final String SELECT = "Select";
	public static final String TP = "Go to";
	public static final String PLACE = "Place";
	/** Tooltip of the header buttons that need a selected placement while there is none. */
	private static final String SELECT_FIRST = "Select a build first: hover it here and click " + SELECT;
	/** Height of those buttons, unless the cell is too small for three of them stacked. */
	private static final int BUTTON_HEIGHT = 20;
	/** Space between the buttons, and between them and the edges of the preview. */
	private static final int BUTTON_GAP = 2;
	/** Width a button needs beyond its label. */
	private static final int BUTTON_PADDING = 16;

	/** Label of the three dots in a hovered cell's top right corner, which open its menu, and of that menu's items. */
	public static final String MORE = "More";
	public static final String RENAME = "Rename";
	public static final String DELETE = "Delete";
	/** Size of the three dots' button, and the space between it and the corner of the cell. */
	private static final int MORE_WIDTH = 14;
	private static final int MORE_HEIGHT = 10;
	private static final int MORE_INSET = 2;
	private static final int MORE_BACKGROUND = 0x80000000;
	private static final int MORE_HOVERED = 0xFF5A5A5A;
	/** Label of the three dots in the top right corner, right of {@link #CREATE}, which open the header's menu, and of its items. */
	public static final String HEADER_MORE = "More options";
	public static final String DESELECT = "Deselect";
	public static final String UNPLACE = "Unplace";
	public static final String RESIZE = "Resize";
	/** Label of the header button right of {@link #CANCEL_PLACE}, shown only while a {@code /vcs resize} is going on. */
	public static final String CANCEL_RESIZE = "✖ Cancel resizing";
	/** Label of the header button left of {@link #CANCEL_RESIZE}, shown with it, which saves the box as {@code /vcs confirmResize} does. */
	public static final String CONFIRM_RESIZE = "✔ Confirm resizing";
	private static final int HEADER_MORE_WIDTH = 20;
	/** Space between the menu's edges and its items. */
	private static final int MENU_PADDING = 2;
	private static final int MENU = 0xF0101018;

	private double scroll;
	/** The header's create button, in the top right corner. */
	private @Nullable Button create;
	/** The header's checkout button, pressable only while a placement is selected. */
	private @Nullable Button checkout;
	/** The header's commit button, pressable only while a placement is selected. */
	private @Nullable Button commit;
	/** The placement the checkout button's tooltip was last made for. */
	private @Nullable ClientPlacement checkoutFor;
	private boolean checkoutTooltipSet;
	/** The header's diff button, which toggles the highlighting of what changed in the selected placement. */
	private @Nullable Button diff;
	/** The placement whose modified blocks were last asked for, or null to ask again. */
	private @Nullable ClientPlacement statusFor;
	/** The diff button's tooltip as last set. */
	private @Nullable String diffTooltip;
	/** The header's cancel place button, in the top left corner, visible only while a {@code /vcs place} copy is being lined up. */
	private @Nullable Button cancelPlace;
	/** The header's confirm place button, left of the cancel place button and shown with it. */
	private @Nullable Button confirmPlace;
	/** The reason the confirm place button's tooltip was last made for, or "" for none; null to make it again. */
	private @Nullable String confirmPlaceTooltipFor;
	/** The header's cancel create button, right of the cancel place button, visible only while a {@code /vcs create} waits for its punch. */
	private @Nullable Button cancelCreate;
	/** The build the cancel create button's tooltip was last made for. */
	private @Nullable String cancelCreateTooltipFor;
	/** The build whose cell the buttons are on, or null while no cell is hovered and they are hidden. */
	private @Nullable BuildSummary buttonsFor;
	/** The build whose menu is open, or null while it is closed; its cell keeps its buttons wherever the mouse goes. */
	private @Nullable BuildSummary menuFor;
	private final Button select = Button.builder(Component.literal(SELECT), button -> choose(PlacementPickerScreen.Action.SELECT)).build();
	private final Button tp = Button.builder(Component.literal(TP), button -> choose(PlacementPickerScreen.Action.TP)).build();
	private final Button place = Button.builder(Component.literal(PLACE), button -> choosePlace()).build();
	private final Button more = new DotsButton(button -> menuFor = buttonsFor);
	/** The menu's items, top to bottom; clicked by {@link #mouseClicked} itself, since they lie over the cell's other buttons. */
	private final Button rename = Button.builder(Component.literal(RENAME), button -> openRename()).build();
	private final Button delete = Button.builder(Component.literal(DELETE), button -> confirmDelete()).build();
	/** The header's three dots, and whether their menu is open; its items are clicked by {@link #mouseClicked} itself. */
	private final Button headerMore = new DotsButton(HEADER_MORE, HEADER_MORE_WIDTH, 20, button -> headerMenuOpen = true);
	private boolean headerMenuOpen;
	private final Button deselect = Button.builder(Component.literal(DESELECT), button -> deselect()).build();
	private final Button unplace = Button.builder(Component.literal(UNPLACE), button -> openUnplace()).build();
	private final Button resize = Button.builder(Component.literal(RESIZE), button -> startResize()).build();
	/** The header's cancel resize button, right of the cancel place button, visible only while a {@code /vcs resize} is going on. */
	private @Nullable Button cancelResize;
	/** The header's confirm resize button, left of the cancel resize button and shown with it. */
	private @Nullable Button confirmResize;
	/** The reason the confirm resize button's tooltip was last made for, or "" for none; null to make it again. */
	private @Nullable String confirmResizeTooltipFor;
	/** The placement the header menu items' tooltips were last made for. */
	private @Nullable ClientPlacement unplaceFor;
	private boolean unplaceTooltipSet;

	public BuildBrowserScreen() {
		super(Component.literal("MCVCS Builds"));
	}

	/** Whether the list has been asked for since this overlay opened; coming back to it from the picker keeps it. */
	private boolean listed;

	@Override
	public void added() {
		// The blocks may have changed since the overlay was last open.
		statusFor = null;
		if (!listed) {
			listed = true;
			BuildBrowser.refresh();
		}
	}

	@Override
	protected void init() {
		int moreLeft = width - GAP - HEADER_MORE_WIDTH;
		headerMore.setRectangle(HEADER_MORE_WIDTH, 20, moreLeft, 6);
		headerMore.setTooltip(Tooltip.create(Component.literal("More options")));
		addRenderableWidget(headerMore);
		closeHeaderMenu();
		unplaceTooltipSet = false;
		int createWidth = Math.max(60, font.width(CREATE) + BUTTON_PADDING);
		int createLeft = moreLeft - 4 - createWidth;
		create = addRenderableWidget(Button.builder(Component.literal(CREATE), button -> openCreate())
			.bounds(createLeft, 6, createWidth, 20)
			.tooltip(Tooltip.create(Component.literal("Save something you built as a new build")))
			.build());
		// Placed by updateCancelButtons: top left, right of the cancel place button while that shows.
		cancelCreate = addRenderableWidget(Button.builder(Component.literal(CANCEL_CREATION), button -> sendCommand("vcs cancelCreate"))
			.bounds(GAP, 6, font.width(CANCEL_CREATION) + BUTTON_PADDING, 20)
			.build());
		cancelCreateTooltipFor = null;
		int checkoutWidth = font.width(CHECKOUT) + BUTTON_PADDING;
		int checkoutLeft = createLeft - 4 - checkoutWidth;
		checkout = addRenderableWidget(Button.builder(Component.literal(CHECKOUT), button -> openCheckout())
			.bounds(checkoutLeft, 6, checkoutWidth, 20)
			.build());
		int commitWidth = font.width(COMMIT) + BUTTON_PADDING;
		int commitLeft = checkoutLeft - 4 - commitWidth;
		commit = addRenderableWidget(Button.builder(Component.literal(COMMIT), button -> openCommit())
			.bounds(commitLeft, 6, commitWidth, 20)
			.build());
		checkoutTooltipSet = false;
		updateCheckout();
		// Placed by updateDiff, since its width follows its label.
		diff = addRenderableWidget(Button.builder(Component.literal(DIFF), button -> toggleDiff())
			.bounds(commitLeft, 6, 0, 20)
			.build());
		diffTooltip = null;
		// Placed by updateCancelButtons: top left, left of the cancel place button.
		confirmPlace = addRenderableWidget(Button.builder(iconLabel(CONFIRM_PLACE, ChatFormatting.GREEN), button -> sendCommand("vcs confirmPlace"))
			.bounds(GAP, 6, font.width(CONFIRM_PLACE) + BUTTON_PADDING, 20)
			.build());
		confirmPlaceTooltipFor = null;
		int cancelPlaceWidth = font.width(CANCEL_PLACE) + BUTTON_PADDING;
		cancelPlace = addRenderableWidget(Button.builder(iconLabel(CANCEL_PLACE, ChatFormatting.RED), button -> sendCommand("vcs cancelPlace"))
			.bounds(GAP, 6, cancelPlaceWidth, 20)
			.tooltip(Tooltip.create(Component.literal("Remove the preview you are placing")))
			.build());
		// Placed by updateCancelButtons: top left, right of the cancel place button while that shows.
		confirmResize = addRenderableWidget(Button.builder(iconLabel(CONFIRM_RESIZE, ChatFormatting.GREEN), button -> sendCommand("vcs confirmResize"))
			.bounds(GAP, 6, font.width(CONFIRM_RESIZE) + BUTTON_PADDING, 20)
			.build());
		confirmResizeTooltipFor = null;
		cancelResize = addRenderableWidget(Button.builder(iconLabel(CANCEL_RESIZE, ChatFormatting.RED), button -> sendCommand("vcs cancelResize"))
			.bounds(GAP, 6, font.width(CANCEL_RESIZE) + BUTTON_PADDING, 20)
			.tooltip(Tooltip.create(Component.literal("Stop resizing and keep the build as it was")))
			.build());
		updateDiff();
		updateCancelButtons();
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

	/** Opens the window asking for a new build's name. */
	private void openCreate() {
		if (minecraft != null) {
			minecraft.setScreen(new CreateBuildScreen(this));
		}
	}

	/** The listed build of the selected placement, or null if none is selected or the list does not have it. */
	private static @Nullable BuildSummary selectedBuild() {
		ClientPlacement selected = ClientPlacements.selected();
		if (selected == null || BuildBrowser.listState() != BuildBrowser.ListState.LOADED) {
			return null;
		}
		return BuildBrowser.builds().stream().filter(build -> build.name().equals(selected.build())).findFirst().orElse(null);
	}

	/** Lets the checkout button be pressed only while a placement is selected, and says which one it checks out into. */
	private void updateCheckout() {
		if (checkout == null) {
			return;
		}
		ClientPlacement selected = selectedBuild() == null ? null : ClientPlacements.selected();
		if (checkoutTooltipSet && Objects.equals(selected, checkoutFor)) {
			return;
		}
		checkoutTooltipSet = true;
		checkoutFor = selected;
		checkout.active = selected != null;
		checkout.setTooltip(Tooltip.create(Component.literal(selected == null
			? SELECT_FIRST
			: "Replace " + selected.label() + " with another saved version")));
		if (commit != null) {
			commit.active = selected != null;
			commit.setTooltip(Tooltip.create(Component.literal(selected == null
				? SELECT_FIRST
				: "Save your changes to " + selected.label() + " as a new version")));
		}
	}

	/** Opens the window that previews and commits the selected placement, see {@link CommitScreen}. */
	private void openCommit() {
		BuildSummary build = selectedBuild();
		ClientPlacement selected = ClientPlacements.selected();
		if (build != null && selected != null && minecraft != null) {
			minecraft.setScreen(new CommitScreen(this, build, selected));
		}
	}

	/** Opens the window that checks out a version into the selected placement, see {@link CheckoutScreen}. */
	private void openCheckout() {
		BuildSummary build = selectedBuild();
		ClientPlacement selected = ClientPlacements.selected();
		if (build != null && selected != null && minecraft != null) {
			minecraft.setScreen(new CheckoutScreen(this, build, selected));
		}
	}

	/**
	 * Labels the diff button {@link #HIDE_DIFF} while a diff is highlighted, and otherwise with how many blocks of the
	 * selected placement changed since the version it holds, asking the server again whenever the selection changes; it
	 * is greyed out while that is awaited or nothing changed. Then lines it up left of {@code Commit}.
	 */
	private void updateDiff() {
		if (diff == null || commit == null) {
			return;
		}
		ClientPlacement selected = selectedBuild() == null ? null : ClientPlacements.selected();
		if (selected != null && !selected.equals(statusFor)) {
			statusFor = selected;
			BuildBrowser.requestStatus();
		}
		String label;
		String tooltip;
		if (DiffManager.active() != null) {
			label = HIDE_DIFF;
			tooltip = "Stop showing changes";
			diff.active = true;
		} else if (selected == null) {
			label = DIFF;
			tooltip = SELECT_FIRST;
			diff.active = false;
		} else {
			PlacementStatusPayload status = BuildBrowser.status();
			int modified = status != null && status.placement().equals(selected.label()) ? status.modified() : PlacementStatusPayload.UNKNOWN;
			if (status == null) {
				label = DIFF + " (...)";
				tooltip = "Counting changes...";
				diff.active = false;
			} else if (modified == PlacementStatusPayload.UNKNOWN) {
				label = DIFF + " (?)";
				tooltip = "Show the blocks you changed since v" + selected.head();
				diff.active = true;
			} else if (modified == 0) {
				label = DIFF + " (0)";
				tooltip = "No changes since v" + selected.head();
				diff.active = false;
			} else {
				label = DIFF + " (" + String.format("%,d", modified) + ")";
				tooltip = "Show the " + String.format("%,d", modified) + (modified == 1 ? " block" : " blocks")
					+ " you changed since v" + selected.head();
				diff.active = true;
			}
		}
		if (!diff.getMessage().getString().equals(label) || !tooltip.equals(diffTooltip)) {
			diffTooltip = tooltip;
			diff.setMessage(Component.literal(label));
			diff.setTooltip(Tooltip.create(Component.literal(tooltip)));
		}
		// As wide as Hide diff at least, so it does not jump about when toggled.
		int diffWidth = Math.max(font.width(HIDE_DIFF), font.width(label)) + BUTTON_PADDING;
		diff.setWidth(diffWidth);
		diff.setX(commit.getX() - 4 - diffWidth);
	}

	/** Runs {@code /vcs changes} against the version the selected placement holds, or {@code /vcs changes off} while one is highlighted. */
	private void toggleDiff() {
		sendCommand(DiffManager.active() != null ? "vcs changes off" : "vcs changes");
	}

	/**
	 * Shows the confirm place and cancel place buttons in the top left corner only while a {@code /vcs place} copy is
	 * being lined up, and the cancel create button right of them, or in that corner on its own, only while a
	 * {@code /vcs create} waits for its punch. The confirm place button is greyed out, saying why, while the copy
	 * stands where it cannot be placed.
	 */
	private void updateCancelButtons() {
		if (confirmPlace == null || cancelPlace == null || cancelResize == null || cancelCreate == null) {
			return;
		}
		String creating = PendingClickHighlight.creating();
		boolean placing = PreviewManager.place() != null;
		confirmPlace.visible = placing;
		cancelPlace.visible = placing;
		ClientResize.Resize resizing = ClientResize.active();
		cancelResize.visible = resizing != null;
		cancelCreate.visible = creating != null;
		if (confirmResize != null) {
			confirmResize.visible = resizing != null;
			String resizeRefusal = resizing == null ? null : resizeRefusal(resizing);
			confirmResize.active = resizeRefusal == null;
			String resizeKey = resizeRefusal == null ? "" : resizeRefusal;
			if (!resizeKey.equals(confirmResizeTooltipFor)) {
				confirmResizeTooltipFor = resizeKey;
				confirmResize.setMessage(resizeRefusal == null ? iconLabel(CONFIRM_RESIZE, ChatFormatting.GREEN) : Component.literal(CONFIRM_RESIZE));
				confirmResize.setTooltip(Tooltip.create(resizeRefusal == null
					? Component.literal("Save the build at its new size as a new version")
					: Component.literal(ClientResize.REFUSED + resizeRefusal).withStyle(ChatFormatting.RED)));
			}
		}
		String refusal = placing ? PlacePreviewKeys.refusal() : null;
		confirmPlace.active = refusal == null;
		String refusalKey = refusal == null ? "" : refusal;
		if (!refusalKey.equals(confirmPlaceTooltipFor)) {
			confirmPlaceTooltipFor = refusalKey;
			// The check is greyed out with the rest of the label while the copy cannot be placed.
			confirmPlace.setMessage(refusal == null ? iconLabel(CONFIRM_PLACE, ChatFormatting.GREEN) : Component.literal(CONFIRM_PLACE));
			confirmPlace.setTooltip(Tooltip.create(refusal == null
				? Component.literal("Place the copy where it is shown")
				: Component.literal(PlacePreviewKeys.REFUSED + refusal).withStyle(ChatFormatting.RED)));
		}
		if (creating != null && !creating.equals(cancelCreateTooltipFor)) {
			cancelCreateTooltipFor = creating;
			cancelCreate.setTooltip(Tooltip.create(Component.literal("Stop creating " + creating)));
		}
		// Left to right, each as wide as its label, unless the buttons on the right leave less room; then shrunk alike to share it.
		List<Button> shown = topLeftButtons().stream().filter(button -> button.visible).toList();
		if (shown.isEmpty()) {
			return;
		}
		int[] widths = shown.stream().mapToInt(button -> font.width(button.getMessage()) + BUTTON_PADDING).toArray();
		int room = (diff != null ? diff.getX() : width) - GAP - GAP - 4 * (shown.size() - 1);
		int total = Arrays.stream(widths).sum();
		if (total > room && room > 0) {
			int left = room;
			for (int i = 0; i < widths.length - 1; i++) {
				widths[i] = (int) ((long) widths[i] * room / total);
				left -= widths[i];
			}
			widths[widths.length - 1] = left;
		}
		int left = GAP;
		for (int i = 0; i < shown.size(); i++) {
			shown.get(i).setX(left);
			shown.get(i).setWidth(widths[i]);
			left += widths[i] + 4;
		}
	}

	/** The buttons of the top left corner, left to right, shown or not. */
	private List<Button> topLeftButtons() {
		return Stream.of(confirmPlace, cancelPlace, confirmResize, cancelResize, cancelCreate).filter(Objects::nonNull).toList();
	}

	/** Why the box of {@code resizing} cannot be saved as it stands, or null when it can. */
	private static @Nullable String resizeRefusal(ClientResize.Resize resizing) {
		String reason = ClientResize.reason();
		if (reason != null) {
			return reason;
		}
		boolean unchanged = ClientPlacements.all().stream()
			.anyMatch(placement -> placement.label().equals(resizing.label()) && placement.box().equals(resizing.box()));
		return unchanged ? "the size is unchanged. Hold " + PlacePreviewKeys.SCROLL.getTranslatedKeyMessage().getString() + " and scroll first" : null;
	}

	/** {@code label}, whose first character is an icon, with that icon drawn in {@code color}. */
	private static Component iconLabel(String label, ChatFormatting color) {
		return Component.empty().append(Component.literal(label.substring(0, 1)).withStyle(color)).append(label.substring(1));
	}

	/** The right edge of the visible cancel buttons in the top left corner, or the screen's left edge if none show. */
	private int cancelButtonsRight() {
		return topLeftButtons().stream().filter(button -> button.visible).mapToInt(Button::getRight).max().orElse(0);
	}

	/**
	 * Runs {@code command} as if typed, keeping the overlay open; sent as a command, so the server checks the
	 * permission and answers in chat the way it does for the command.
	 */
	private void sendCommand(String command) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.connection.sendCommand(command);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Opens the window that removes the selected placement from the world, see {@link UnplaceScreen}. */
	private void openUnplace() {
		ClientPlacement selected = selectedBuild() == null ? null : ClientPlacements.selected();
		closeHeaderMenu();
		if (selected != null && minecraft != null) {
			minecraft.setScreen(new UnplaceScreen(this, selected));
		}
	}

	/** Closes the overlay, so the player can drag the box in the world, and runs {@code /vcs resize}. */
	private void startResize() {
		closeHeaderMenu();
		if (selectedBuild() != null && ClientPlacements.selected() != null) {
			onClose();
			sendCommand("vcs resize");
		}
	}

	/** Runs {@code /vcs deselect}, keeping the overlay open. */
	private void deselect() {
		closeHeaderMenu();
		if (ClientPlacements.selected() != null) {
			sendCommand("vcs deselect");
		}
	}

	/** Lets the header menu's items be pressed only while a placement is selected, and says which one they act on. */
	private void updateUnplace() {
		ClientPlacement selected = selectedBuild() == null ? null : ClientPlacements.selected();
		if (unplaceTooltipSet && Objects.equals(selected, unplaceFor)) {
			return;
		}
		unplaceTooltipSet = true;
		unplaceFor = selected;
		unplace.active = selected != null;
		unplace.setTooltip(Tooltip.create(Component.literal(selected == null
			? SELECT_FIRST
			: "Remove " + selected.label() + " from the world")));
		resize.active = selected != null;
		resize.setTooltip(Tooltip.create(Component.literal(selected == null
			? SELECT_FIRST
			: "Make the box of " + selected.label() + " bigger or smaller")));
		deselect.active = selected != null;
		deselect.setTooltip(Tooltip.create(Component.literal(selected == null
			? "No build is selected"
			: "Stop selecting " + selected.label())));
	}

	private List<Button> headerMenuItems() {
		return List.of(deselect, resize, unplace);
	}

	private void closeHeaderMenu() {
		headerMenuOpen = false;
		for (Button item : headerMenuItems()) {
			item.visible = false;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		updateCheckout();
		updateDiff();
		updateCancelButtons();
		updateUnplace();
		// Under the open header menu, nothing else lights up or shows its tooltip.
		int behindX = headerMenuOpen ? -1 : mouseX;
		int behindY = headerMenuOpen ? -1 : mouseY;
		super.extractRenderState(graphics, behindX, behindY, partialTick);
		// Centred, unless the header's buttons reach that far; then between them, as close to the centre as they leave
		// room for, or left out if they leave none.
		int buttonsLeft = diff != null ? diff.getX() : width;
		int titleWidth = font.width(title);
		int titleX = Math.max(cancelButtonsRight() + GAP, Math.min((width - titleWidth) / 2, buttonsLeft - GAP - titleWidth));
		if (titleX + titleWidth <= buttonsLeft - GAP) {
			graphics.text(font, title, titleX, 12, WHITE);
		}

		switch (BuildBrowser.listState()) {
			case NONE, LOADING -> loading(graphics, width / 2, height / 2, "Loading builds");
			case DENIED -> graphics.centeredText(font, "Builds need cheats turned on (operator permission)", width / 2, height / 2, RED);
			case LOADED -> {
				if (BuildBrowser.builds().isEmpty()) {
					graphics.centeredText(font, "No builds yet. Click " + CREATE + " to save your first one", width / 2, height / 2, GREY);
				} else {
					grid(graphics, behindX, behindY, partialTick);
				}
			}
		}
		if (headerMenuOpen) {
			menu(graphics, headerMore, headerMenuItems(), mouseX, mouseY, partialTick);
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
			menu(graphics, more, menuItems(), mouseX, mouseY, partialTick);
		} else if (hovered != null && !headerMenuOpen && !overButton(mouseX, mouseY)) {
			// Over a button, that button's own tooltip says what it does instead.
			graphics.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
		}
	}

	/** The open menu of {@code items}, hanging from the three dots {@code dots} and lined up with their right edge, over everything else. */
	private void menu(GuiGraphicsExtractor graphics, Button dots, List<Button> items, int mouseX, int mouseY, float partialTick) {
		int itemWidth = items.stream().mapToInt(item -> font.width(item.getMessage())).max().orElse(0) + BUTTON_PADDING;
		int right = dots.getRight();
		int top = dots.getBottom() + 1;
		int left = right - itemWidth - 2 * MENU_PADDING;
		int bottom = top + items.size() * BUTTON_HEIGHT + (items.size() - 1) * BUTTON_GAP + 2 * MENU_PADDING;
		for (int i = 0; i < items.size(); i++) {
			items.get(i).visible = true;
			items.get(i).setRectangle(itemWidth, BUTTON_HEIGHT, left + MENU_PADDING, top + MENU_PADDING + i * (BUTTON_HEIGHT + BUTTON_GAP));
		}

		graphics.nextStratum();
		graphics.fill(left, top, right, bottom, MENU);
		graphics.outline(left - 1, top - 1, right - left + 2, bottom - top + 2, BORDER_HOVERED);
		for (Button item : items) {
			item.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	private List<Button> menuItems() {
		return List.of(rename, delete);
	}

	private void closeMenu() {
		menuFor = null;
		for (Button item : menuItems()) {
			item.visible = false;
		}
	}

	/** Opens the window that renames the build whose menu is open, see {@link RenameBuildScreen}. */
	private void openRename() {
		BuildSummary build = menuFor;
		closeMenu();
		if (build != null && minecraft != null) {
			minecraft.setScreen(new RenameBuildScreen(this, build));
		}
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
				loading(graphics, centerX, centerY, "Loading");
			} else {
				placeholder(graphics, build, x, y, size);
			}
		} else {
			switch (thumbnail.state()) {
				case REQUESTED -> loading(graphics, centerX, centerY, "Loading");
				case DOWNLOADING -> loading(graphics, centerX, centerY, "Loading " + (int) (thumbnail.progress() * 100) + "%");
				case MESHING -> loading(graphics, centerX, centerY, "Loading");
				case FAILED -> wrapped(graphics, thumbnail.failure() + (thumbnail.retryable() ? ". Reopen to try again" : ""), x, y, size, RED);
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
	}

	/** A build too big to download by itself: says so, and how big it is. */
	private void placeholder(GuiGraphicsExtractor graphics, BuildSummary build, int x, int y, int size) {
		wrapped(graphics, "Large build (" + String.format("%,d", build.extent().volume()) + " blocks). Click to show preview", x, y, size, GREY);
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
			case 0 -> "Not in this world";
			case 1 -> "In the world once";
			default -> "In the world " + build.placements() + " times";
		};
		List<Component> lines = new ArrayList<>(List.of(
			Component.literal(build.name() + " " + build.versionLabel()), Component.literal(size), Component.literal(placements)));
		if (waitsForClick(build)) {
			lines.add(Component.literal("Click to show preview"));
		}
		return lines;
	}

	/** Whether {@code build}'s preview is not downloaded until its cell is clicked, and that click has not come yet. */
	private static boolean waitsForClick(BuildSummary build) {
		return BuildBrowser.thumbnail(build) == null && !BuildBrowser.downloadsByItself(build);
	}

	/** How far the previews have turned by now: the same for every cell, so they turn together. */
	static float yaw() {
		double speed = ClientConfig.settings().rotationSpeed();
		return (float) ((START_YAW + Util.getMillis() / 1000.0 * speed) % 360.0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// An open menu takes the click: its item does its thing, and anywhere else, the dots included, closes it.
		if (headerMenuOpen) {
			if (headerMenuItems().stream().noneMatch(item -> item.mouseClicked(event, doubleClick))) {
				closeHeaderMenu();
			}
			return true;
		}
		if (menuFor != null) {
			if (menuItems().stream().noneMatch(item -> item.mouseClicked(event, doubleClick))) {
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

	/**
	 * Starts {@code /vcs place} for the build the buttons are on: straight away for its only version, otherwise once
	 * {@link VersionPickerScreen} is told which.
	 */
	private void choosePlace() {
		BuildSummary build = buttonsFor;
		if (build == null || minecraft == null) {
			return;
		}
		if (build.versions().size() > 1) {
			minecraft.setScreen(new VersionPickerScreen(this, build));
		} else {
			run(target -> VersionPickerScreen.placeCommand(target, target.latest()));
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
			Tooltip notPlaced = Tooltip.create(Component.literal("Not in this world. Click " + PLACE + " first"));
			select.active = placed;
			select.setTooltip(!placed ? notPlaced : Tooltip.create(Component.literal(build.placements() == 1
				? "Pick it for " + COMMIT + ", " + CHECKOUT + " and " + DIFF
				: "It is in the world " + build.placements() + " times. Choose which one to pick")));
			tp.active = placed;
			tp.setTooltip(!placed ? notPlaced : Tooltip.create(Component.literal(build.placements() == 1
				? "Teleport to it"
				: "It is in the world " + build.placements() + " times. Choose which one to go to")));
			place.setTooltip(Tooltip.create(Component.literal(build.versions().size() == 1
				? "Put a copy where you stand"
				: "Choose a version to put where you stand")));
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
		if (event.isEscape() && headerMenuOpen) {
			closeHeaderMenu();
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
		return Stream.concat(buttons().stream(), Stream.concat(Stream.of(more), menuItems().stream()))
			.filter(button -> button.visible && button.getMessage().getString().equals(label)).findFirst().orElse(null);
	}

	/** The item labelled {@code label} in the open header menu, for tests to click; null while the menu is closed. */
	public @Nullable Button headerMenuItemOf(String label) {
		if (!headerMenuOpen) {
			return null;
		}
		return headerMenuItems().stream().filter(item -> item.getMessage().getString().equals(label)).findFirst().orElse(null);
	}

	/** Three dots in a row, lighter behind them under the mouse, which open a cell's menu or the header's. */
	private static final class DotsButton extends Button {
		DotsButton(OnPress onPress) {
			this(MORE, MORE_WIDTH, MORE_HEIGHT, onPress);
		}

		DotsButton(String label, int width, int height, OnPress onPress) {
			super(0, 0, width, height, Component.literal(label), onPress, DEFAULT_NARRATION);
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
