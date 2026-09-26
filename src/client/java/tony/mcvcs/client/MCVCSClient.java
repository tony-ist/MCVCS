package tony.mcvcs.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;

import tony.mcvcs.client.browser.BuildBrowser;
import tony.mcvcs.client.browser.BuildBrowserKey;
import tony.mcvcs.client.browser.BuildPreviewRenderer;

import tony.mcvcs.client.diff.DiffHighlightRenderer;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.config.ClientConfig;
import tony.mcvcs.client.label.BuildLabelRenderer;
import tony.mcvcs.client.place.PlacePreviewKeys;
import tony.mcvcs.client.place.PlacePreviewRenderer;
import tony.mcvcs.client.place.PlacePreviewStatus;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.build.ClientPlacements;
import tony.mcvcs.client.selection.PendingClickHighlight;
import tony.mcvcs.client.selection.SelectHotkey;
import tony.mcvcs.client.selection.SelectionBoxRenderer;

public class MCVCSClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.register();
		PreviewManager.register();
		DiffManager.register();
		ClientPlacements.register();
		SelectionBoxRenderer.register();
		SelectHotkey.register();
		PendingClickHighlight.register();
		PlacePreviewKeys.register();
		PlacePreviewStatus.register();
		PlacePreviewRenderer.register();
		BuildLabelRenderer.register();
		DiffHighlightRenderer.register();
		BuildBrowser.register();
		BuildBrowserKey.register();
		PictureInPictureRendererRegistry.register(context -> new BuildPreviewRenderer(context.bufferSource()));
	}
}
