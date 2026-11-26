package tony.mcvcs.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Gives the mod a Config button in Mod Menu, opening {@link ClientConfigScreen}. Only Mod Menu loads this class. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ClientConfigScreen::new;
	}
}
