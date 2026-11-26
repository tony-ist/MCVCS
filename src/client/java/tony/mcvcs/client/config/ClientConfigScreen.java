package tony.mcvcs.client.config;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The settings of {@link ClientConfig} as a vanilla options screen, opened by Mod Menu's Config button. The settings
 * are saved to the file and put in force when the screen is closed, however it is closed.
 * <p>
 * The turning speed is set in whole degrees per second, and the auto download limit picked from a few steps, keeping
 * any other value the file holds among them. A setting left untouched keeps the exact value the file gave it.
 */
public final class ClientConfigScreen extends OptionsSubScreen {
	/** Auto download limits to pick from: never, a power of ten in between, and always. */
	private static final List<Integer> AUTO_DOWNLOAD_STEPS = List.of(0, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, Integer.MAX_VALUE);

	private final ClientConfig.Settings initial;
	private final OptionInstance<Boolean> showLabels;
	private final OptionInstance<Boolean> showSelectionBox;
	private final OptionInstance<Integer> rotationSpeed;
	private final OptionInstance<Integer> cellSize;
	private final OptionInstance<Integer> autoDownloadLimit;

	public ClientConfigScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("options.mcvcs.title"));
		initial = ClientConfig.settings();

		showLabels = OptionInstance.createBoolean("options.mcvcs.show_labels",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.mcvcs.show_labels.tooltip")), initial.showLabels());
		showSelectionBox = OptionInstance.createBoolean("options.mcvcs.show_selection_box",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.mcvcs.show_selection_box.tooltip")), initial.showSelectionBox());
		rotationSpeed = new OptionInstance<>("options.mcvcs.rotation_speed",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.mcvcs.rotation_speed.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, Component.translatable("options.mcvcs.rotation_speed.value", value)),
			new OptionInstance.IntRange(0, (int) ClientConfig.MAX_ROTATION_SPEED),
			(int) Math.round(initial.rotationSpeed()), value -> {});
		cellSize = new OptionInstance<>("options.mcvcs.cell_size",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.mcvcs.cell_size.tooltip")),
			Options::genericValueLabel,
			new OptionInstance.IntRange(ClientConfig.MIN_CELL_SIZE, ClientConfig.MAX_CELL_SIZE),
			initial.cellSize(), value -> {});
		autoDownloadLimit = new OptionInstance<>("options.mcvcs.auto_download_limit",
			OptionInstance.cachedConstantTooltip(Component.translatable("options.mcvcs.auto_download_limit.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, autoDownloadLabel(value)),
			new OptionInstance.SliderableEnum<>(autoDownloadSteps(initial.autoDownloadLimit()), Codec.INT),
			initial.autoDownloadLimit(), value -> {});
	}

	@Override
	protected void addOptions() {
		if (list == null) {
			return;
		}
		list.addHeader(Component.translatable("options.mcvcs.world"));
		list.addSmall(showLabels, showSelectionBox);
		list.addHeader(Component.translatable("options.mcvcs.overlay"));
		list.addSmall(rotationSpeed, cellSize, autoDownloadLimit);
	}

	/** Saves the settings on the way out, whether by Done or Escape. */
	@Override
	public void removed() {
		super.removed();
		ClientConfig.Settings settings = settings();
		if (!settings.equals(ClientConfig.settings())) {
			ClientConfig.save(settings);
		}
	}

	/** The settings as the screen shows them now. */
	public ClientConfig.Settings settings() {
		// The slider shows whole degrees; one left where it started keeps the fraction the file may have given.
		double speed = rotationSpeed.get() == Math.round(initial.rotationSpeed()) ? initial.rotationSpeed() : rotationSpeed.get();
		return new ClientConfig.Settings(speed, cellSize.get(), autoDownloadLimit.get(), showLabels.get(), showSelectionBox.get());
	}

	/** Where the toggle for {@link ClientConfig.Settings#showLabels} is laid out now, for tests to click; null if nowhere. */
	public @Nullable ScreenRectangle showLabelsButton() {
		AbstractWidget widget = list == null ? null : list.findOption(showLabels);
		return widget == null ? null : widget.getRectangle();
	}

	/** {@link #AUTO_DOWNLOAD_STEPS}, with {@code current} put in its place among them if it is none of them. */
	private static List<Integer> autoDownloadSteps(int current) {
		List<Integer> steps = new ArrayList<>(AUTO_DOWNLOAD_STEPS);
		if (!steps.contains(current)) {
			int index = 0;
			while (steps.get(index) < current) {
				index++;
			}
			steps.add(index, current);
		}
		return List.copyOf(steps);
	}

	private static Component autoDownloadLabel(int limit) {
		if (limit == 0) {
			return Component.translatable("options.mcvcs.auto_download_limit.never");
		}
		if (limit == Integer.MAX_VALUE) {
			return Component.translatable("options.mcvcs.auto_download_limit.always");
		}
		// Short enough for a half-width slider: 1M, 10K, or a value of the file's own such as 2,500.
		String count = limit % 1_000_000 == 0 ? limit / 1_000_000 + "M" : limit % 1_000 == 0 ? limit / 1_000 + "K" : String.format("%,d", limit);
		return Component.translatable("options.mcvcs.auto_download_limit.value", count);
	}
}
