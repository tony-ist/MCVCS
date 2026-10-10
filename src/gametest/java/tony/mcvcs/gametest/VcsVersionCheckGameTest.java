package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.screenshotLastFrame;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import tony.mcvcs.network.ModVersionPayload;
import tony.mcvcs.network.VersionCheck;

/** A client with the same version as the server is not warned on join; one with another version is. */
@SuppressWarnings("UnstableApiUsage")
public class VcsVersionCheckGameTest extends VcsGameTest {
	/** Every game message the client has received, filled on the client thread. */
	private static final List<String> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) RECEIVED.add(message.getString()); });
	}

	@Override
	protected void run(ClientGameTestContext context) {
		context.runOnClient(client -> RECEIVED.clear());
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			context.waitTicks(5);
			List<String> onJoin = warnings(context);
			if (!onJoin.isEmpty()) {
				throw new AssertionError("Expected no version warning with matching versions but got " + onJoin);
			}

			context.runOnClient(client -> ClientPlayNetworking.send(new ModVersionPayload("0.0.1-other")));
			context.waitTicks(5);
			String expected = VersionCheck.mismatch(VersionCheck.version(), "0.0.1-other").getString();
			List<String> mismatch = warnings(context);
			if (!mismatch.equals(List.of(expected))) {
				throw new AssertionError("Expected '" + expected + "' but got " + mismatch);
			}
			screenshotLastFrame(context, "mcvcs-version-mismatch");
		}
	}

	private static List<String> warnings(ClientGameTestContext context) {
		return context.computeOnClient(client -> RECEIVED.stream().filter(message -> message.contains("MCVCS")).toList());
	}
}
