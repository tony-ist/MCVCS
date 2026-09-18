package tony.mcvcs.command;

import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.world.World;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildBox;
import tony.mcvcs.build.BuildRegistry;
import tony.mcvcs.network.ChatButtons;

/**
 * {@code /vcs weselect}: makes the selected build's box the player's WorldEdit selection, the corners set as
 * {@code //pos1} and {@code //pos2} would set them, so WorldEdit commands act on exactly the build. The build itself
 * is not touched: its box only ever changes through {@code /vcs expand}, so moving the WorldEdit selection afterwards
 * cannot move the build.
 */
public final class VcsCommandWeselect {
	static final VcsHelp HELP = new VcsHelp("weselect", "/vcs weselect",
		"make the build's box your WorldEdit selection",
		"Sets your WorldEdit selection to the whole box of your selected build.");

	private VcsCommandWeselect() {
	}

	static int run(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<Build> selected = BuildRegistry.selected(player);
		if (selected.isEmpty()) {
			source.sendFailure(VcsMessages.noBuildSelected());
			return 0;
		}

		Build build = selected.get();
		// WorldEdit keeps one selection per session, in the world the player is in: setting it for another world would
		// leave it looking empty here, so the player is sent to the build instead.
		if (!player.level().dimension().equals(build.dimension())) {
			source.sendFailure(Component.literal("Build ").append(VcsMessages.name(build.name())).append(" is in " + build.dimension().identifier()
				+ " and you are in " + player.level().dimension().identifier() + "; run ").append(ChatButtons.command("/vcs tp " + build.name())).append(" first"));
			return 0;
		}

		BuildBox box = build.box();
		FabricAdapter adapter = FabricAdapter.get();
		Player actor = adapter.fromNativePlayer(player);
		LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);
		World world = adapter.fromNativeWorld(player.level());

		RegionSelector selector = new CuboidRegionSelector(world, adapter.adapt(box.min()), adapter.adapt(box.max()));
		session.setRegionSelector(world, selector);
		// Sends the new corners to a client running WorldEditCUI, which is what //pos1 and //pos2 do after setting one.
		selector.explainRegionAdjust(actor, session);

		source.sendSuccess(() -> Component.literal("Selected build ").append(VcsMessages.name(build.name()))
			.append(" with WorldEdit: " + box.min().toShortString() + " to " + box.max().toShortString() + " (" + VcsMessages.size(box) + ", " + box.volume() + " blocks)"), false);
		return 1;
	}
}
