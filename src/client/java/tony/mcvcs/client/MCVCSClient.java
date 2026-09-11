package tony.mcvcs.client;

import net.fabricmc.api.ClientModInitializer;

import tony.mcvcs.client.preview.PreviewManager;
import tony.mcvcs.client.selection.SelectionBoxRenderer;

public class MCVCSClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PreviewManager.register();
		SelectionBoxRenderer.register();
	}
}
