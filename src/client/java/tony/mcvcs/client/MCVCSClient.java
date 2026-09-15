package tony.mcvcs.client;

import net.fabricmc.api.ClientModInitializer;

import tony.mcvcs.client.diff.DiffHighlightRenderer;
import tony.mcvcs.client.diff.DiffManager;
import tony.mcvcs.client.label.BuildLabelRenderer;
import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.build.ClientBuilds;
import tony.mcvcs.client.selection.SelectHotkey;
import tony.mcvcs.client.selection.SelectionBoxRenderer;

public class MCVCSClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PreviewManager.register();
		DiffManager.register();
		ClientBuilds.register();
		SelectionBoxRenderer.register();
		SelectHotkey.register();
		BuildLabelRenderer.register();
		DiffHighlightRenderer.register();
	}
}
