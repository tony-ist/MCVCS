package tony.mcvcs.gametest;

import static tony.mcvcs.gametest.VcsTestSupport.buildFile;
import static tony.mcvcs.gametest.VcsTestSupport.fillBox;
import static tony.mcvcs.gametest.VcsTestSupport.playerPos;
import static tony.mcvcs.gametest.VcsTestSupport.resetBuilds;
import static tony.mcvcs.gametest.VcsTestSupport.runCommand;
import static tony.mcvcs.gametest.VcsTestSupport.schematic;
import static tony.mcvcs.gametest.VcsTestSupport.select;
import static tony.mcvcs.gametest.VcsTestSupport.setBlock;
import static tony.mcvcs.gametest.VcsTestSupport.suggestions;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildRegistry;

/**
 * {@code /vcs tag <version> <tagname>} tags a version of the selected placement's build, and {@code /vcs commit
 * <tagname>} tags the version it saves; tags are written to {@code build.json}, shown by {@code /vcs builds}, and a tag
 * already in use or with characters a tag may not have is refused. Every command taking a version number completes
 * and takes a tag in its place.
 */
@SuppressWarnings("UnstableApiUsage")
public class VcsTagCommandGameTest extends VcsGameTest {
	private static final String BUILD_NAME = "gametest-tag";

	/** Every game message the client has received, filled on the client thread. */
	private static final List<Component> RECEIVED = new ArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> RECEIVED.add(message));
	}

	@Override
	protected void run(ClientGameTestContext context) {
		resetBuilds(BUILD_NAME);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
			singleplayer.getClientLevel().waitForChunksRender();

			BlockPos min = playerPos(singleplayer).offset(2, 1, 2);
			BlockPos max = min.offset(2, 1, 1);
			fillBox(singleplayer, min, max, Blocks.STONE.defaultBlockState(), min, Blocks.GOLD_BLOCK.defaultBlockState());
			select(singleplayer, min, max);

			// Nothing to tag before a placement is selected.
			assertMessages(run(context, "vcs tag 1 1.0.0"), List.of("No placement selected"), false);

			runCommand(context, "vcs create " + BUILD_NAME + " -we");
			assertTags(singleplayer, Map.of());

			// Tag v1; the version is suggested.
			if (!suggestions(singleplayer, "vcs tag ").contains("1")) {
				throw new AssertionError("Expected /vcs tag to suggest v1 but got " + suggestions(singleplayer, "vcs tag "));
			}
			assertMessages(run(context, "vcs tag 1 1.0.0"), List.of("Tagged build " + BUILD_NAME + " v1 (1.0.0)"), true);
			assertTags(singleplayer, Map.of("1.0.0", 1));

			// A second tag on the same version is fine; tags are listed in order.
			assertMessages(run(context, "vcs tag 1 first_Release+x"), List.of("Tagged build " + BUILD_NAME + " v1 (1.0.0, first_Release+x)"), true);

			// Commit with a tag: the new version carries it.
			setBlock(singleplayer, max, Blocks.DIAMOND_BLOCK.defaultBlockState());
			assertMessages(run(context, "vcs commit 2.0.0-rc.1"), List.of("Committed " + BUILD_NAME + "/" + Build.MAIN + " as build " + BUILD_NAME + " v2 (2.0.0-rc.1)"), true);
			assertTags(singleplayer, Map.of("1.0.0", 1, "first_Release+x", 1, "2.0.0-rc.1", 2));

			// A tag already naming a version is refused, by tag and by commit, and the commit saves nothing.
			assertMessages(run(context, "vcs tag 2 1.0.0"), List.of("Tag 1.0.0 already names v1 of build " + BUILD_NAME), true);
			assertMessages(run(context, "vcs commit 1.0.0"), List.of("Tag 1.0.0 already names v1 of build " + BUILD_NAME + "; nothing was committed"), true);
			if (Files.exists(schematic(BUILD_NAME, 3))) {
				throw new AssertionError("A commit refused for its tag must not write " + schematic(BUILD_NAME, 3));
			}
			assertMessages(run(context, "vcs tag 1 1.0.0"), List.of("v1 of build " + BUILD_NAME + " is already tagged 1.0.0"), true);

			// Characters a tag may not have do not even parse as one, and versions the build does not have are refused.
			run(context, "vcs tag 2 bad@tag");
			run(context, "vcs commit bad@tag");
			if (Files.exists(schematic(BUILD_NAME, 3))) {
				throw new AssertionError("A commit with an invalid tag must not write " + schematic(BUILD_NAME, 3));
			}
			assertMessages(run(context, "vcs tag 5 5.0.0"), List.of("Build " + BUILD_NAME + " only has versions 1 to 2"), true);
			assertTags(singleplayer, Map.of("1.0.0", 1, "first_Release+x", 1, "2.0.0-rc.1", 2));

			// A plain commit still works and has no tag.
			assertMessages(run(context, "vcs commit"), List.of("Committed " + BUILD_NAME + "/" + Build.MAIN + " as build " + BUILD_NAME + " v3 ("), true);

			// Digits alone would read as a version number, so they are no tag.
			assertMessages(run(context, "vcs tag 3 42"), List.of("Invalid tag '42'"), true);

			// Every command taking a version completes tags alongside the numbers...
			List<String> all = List.of("1", "2", "3", "1.0.0", "2.0.0-rc.1", "first_Release+x");
			for (String command : List.of("vcs checkout ", "vcs diff ", "vcs preview ", "vcs load ", "vcs tag ", "vcs place " + BUILD_NAME + " ")) {
				List<String> offered = suggestions(singleplayer, command);
				if (!offered.containsAll(all)) {
					throw new AssertionError("Expected '/" + command + "' to suggest " + all + " but got " + offered);
				}
			}
			// ...narrowed down by what has been typed.
			List<String> narrowed = suggestions(singleplayer, "vcs checkout 2.");
			if (!narrowed.equals(List.of("2.0.0-rc.1"))) {
				throw new AssertionError("Expected '/vcs checkout 2.' to suggest only 2.0.0-rc.1 but got " + narrowed);
			}

			// ...and takes a tag wherever it takes a number.
			assertMessages(run(context, "vcs checkout 1.0.0"), List.of("Checked out " + BUILD_NAME + "/" + Build.MAIN + " v1 (1.0.0, first_Release+x) ("), true);
			assertMessages(run(context, "vcs diff 2.0.0-rc.1"), List.of("1 block in " + BUILD_NAME + "/" + Build.MAIN + " differs from v2"), true);
			run(context, "vcs diff off");
			assertMessages(run(context, "vcs load first_Release+x"), List.of("Loaded build " + BUILD_NAME + " v1 (1.0.0, first_Release+x) ("), true);
			assertMessages(run(context, "vcs tag 2.0.0-rc.1 stable"), List.of("Tagged build " + BUILD_NAME + " v2 (2.0.0-rc.1, stable)"), true);
			assertMessages(run(context, "vcs checkout nope"), List.of("Build " + BUILD_NAME + " has no version tagged nope"), true);
			assertMessages(run(context, "vcs diff nope"), List.of("Build " + BUILD_NAME + " has no version tagged nope"), true);
			assertMessages(run(context, "vcs place " + BUILD_NAME + " nope"), List.of("Build " + BUILD_NAME + " has no version tagged nope"), true);

			// /vcs builds shows the tags of the version each placement holds.
			run(context, "vcs checkout stable");
			List<Component> builds = run(context, "vcs builds");
			List<String> texts = builds.stream().map(Component::getString).toList();
			if (texts.stream().noneMatch(line -> line.startsWith("    Placement " + Build.MAIN + " v2 (2.0.0-rc.1, stable) ("))) {
				throw new AssertionError("Expected /vcs builds to show v2's tag but got " + texts);
			}

			String json = readBuildFile();
			if (!json.contains("\"tags\"") || !json.contains("\"2.0.0-rc.1\"")) {
				throw new AssertionError("Expected tags in build.json but got " + json);
			}

			context.takeScreenshot("mcvcs-vcs-tag");
		}
	}

	/** The build as the server has it now has exactly {@code expected} as its tags. */
	private static void assertTags(TestSingleplayerContext singleplayer, Map<String, Integer> expected) {
		Build build = singleplayer.getServer().computeOnServer(server -> BuildRegistry.find(server, BUILD_NAME))
			.orElseThrow(() -> new AssertionError("Expected build '" + BUILD_NAME + "' to exist"));
		if (!build.tags().equals(expected)) {
			throw new AssertionError("Expected tags " + expected + " but got " + build.tags());
		}
	}

	private static String readBuildFile() {
		try {
			return Files.readString(buildFile(BUILD_NAME));
		} catch (IOException e) {
			throw new AssertionError("Failed to read " + buildFile(BUILD_NAME), e);
		}
	}

	/** Runs {@code command} and returns every game message it produced, in order. */
	private static List<Component> run(ClientGameTestContext context, String command) {
		context.runOnClient(client -> RECEIVED.clear());
		runCommand(context, command);
		context.waitTicks(5);
		return context.computeOnClient(client -> List.copyOf(RECEIVED));
	}

	/**
	 * With {@code exact}, the messages are exactly {@code prefixes} long and each starts with its prefix; without, only
	 * that there is at least one message, for a refusal whose wording belongs to another class.
	 */
	private static void assertMessages(List<Component> messages, List<String> prefixes, boolean exact) {
		List<String> texts = messages.stream().map(Component::getString).toList();
		if (!exact) {
			if (texts.isEmpty()) {
				throw new AssertionError("Expected a message but got none");
			}
			return;
		}
		if (texts.size() != prefixes.size()) {
			throw new AssertionError("Expected messages starting with " + prefixes + " but got " + texts);
		}
		for (int i = 0; i < prefixes.size(); i++) {
			if (!texts.get(i).startsWith(prefixes.get(i))) {
				throw new AssertionError("Expected message " + i + " to start with '" + prefixes.get(i) + "' but got " + texts);
			}
		}
	}
}
