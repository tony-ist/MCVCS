package tony.mcvcs;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import tony.mcvcs.command.PendingClick;
import tony.mcvcs.command.VcsCommand;
import tony.mcvcs.migration.Migrations;
import tony.mcvcs.network.DiffSender;
import tony.mcvcs.network.PreviewSender;
import tony.mcvcs.network.BuildSync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCVCS implements ModInitializer {
	public static final String MOD_ID = "mcvcs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Migrations.register();
		PreviewSender.register();
		DiffSender.register();
		BuildSync.register();
		VcsCommand.register();
		PendingClick.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
