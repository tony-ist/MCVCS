package tony.mcvcs.gametest;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for the {@code /vcs} game tests that lets a run be narrowed to a few of them. Fabric can only filter
 * client game tests by mod ID, and all of ours are in one mod, so the filter lives here instead: when the
 * {@code mcvcs.gametest} system property names one or more test classes (simple names, comma-separated), every other
 * test returns without doing anything. A test that does nothing is still on the title screen afterwards, which is
 * all the runner checks between tests. {@code build.gradle} sets the property from the {@code gametest} project
 * property, so {@code gradlew runClientGameTest -Pgametest=VcsCheckoutCommandGameTest} runs just that test.
 */
@SuppressWarnings("UnstableApiUsage")
abstract class VcsGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcvcs-test");
	private static final String FILTER_PROPERTY = "mcvcs.gametest";

	/** Simple class names of the tests to run, or {@code null} when every test runs. */
	private static final Set<String> SELECTED = selected();

	private static Set<String> selected() {
		String filter = System.getProperty(FILTER_PROPERTY);
		if (filter == null || filter.isBlank()) {
			return null;
		}
		return Arrays.stream(filter.split(",")).map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toSet());
	}

	@Override
	public final void runTest(ClientGameTestContext context) {
		String name = getClass().getSimpleName();
		if (SELECTED != null && !SELECTED.contains(name)) {
			LOGGER.info("Skipping {}: not in -D{}={}", name, FILTER_PROPERTY, SELECTED);
			return;
		}
		run(context);
	}

	/** The test itself; called by {@link #runTest} unless the test has been filtered out. */
	protected abstract void run(ClientGameTestContext context);
}
