package tony.mcvcs.client.browser;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import tony.mcvcs.network.BuildSummary;

/**
 * Asks which version the builds overlay's {@code Place} button means, for a build that has several, see
 * {@link VersionGridScreen}. Clicking one closes the window and the overlay and runs {@code /vcs place} for that
 * version, as if typed. {@code Cancel} goes back to the overlay.
 */
public final class VersionPickerScreen extends VersionGridScreen {
	/** Height of the strip at the top of the window holding its header. */
	private static final int HEADER = 24;
	/** Height of the strip at the bottom of the window holding the cancel button. */
	private static final int FOOTER = 32;

	public VersionPickerScreen(BuildBrowserScreen parent, BuildSummary build) {
		super(Component.literal("Place a version of " + build.name()), parent, build);
	}

	/** The command that places {@code version} of {@code build}, without the slash. */
	static String placeCommand(BuildSummary build, BuildSummary.Version version) {
		return "vcs place " + build.name() + " " + version.version();
	}

	@Override
	protected int headerHeight() {
		return HEADER;
	}

	@Override
	protected int footerHeight() {
		return FOOTER;
	}

	@Override
	protected void extractHeader(GuiGraphicsExtractor graphics, Layout layout, int top) {
		graphics.centeredText(font, fit(title.getString(), layout.width() - 2 * GAP), width / 2, top + (HEADER - font.lineHeight) / 2 + 1, WHITE);
	}

	@Override
	protected void initFooter(Layout layout) {
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
			.bounds(width / 2 - 40, layout.gridBottom() + (FOOTER - 20) / 2, 80, 20)
			.build());
	}

	@Override
	protected Component tooltip(BuildSummary.Version version) {
		return Component.literal("Place a copy of " + version.label() + " where you stand");
	}

	/** Closes the window and the overlay and runs {@code /vcs place} for {@code version}, as if typed. */
	@Override
	protected void clicked(BuildSummary.Version version) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft != null) {
			minecraft.setScreen(null);
		}
		if (player != null) {
			// Sent as a command, so the server checks the permission and answers in chat the way it does for the command.
			player.connection.sendCommand(placeCommand(build, version));
		}
	}
}
