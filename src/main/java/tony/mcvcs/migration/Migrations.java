package tony.mcvcs.migration;

import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.BuildStorage;

/**
 * Runs every {@link Migration}, in order, as the server starts and before any command or player can reach the
 * {@code mcvcs/} folder, so the rest of the mod only ever sees the current file layout. A migration that fails
 * stops the server from starting: carrying on would leave builds the mod can no longer read.
 */
public final class Migrations {
	/** Every migration, oldest first. */
	private static final List<Migration> ALL = List.of(
		new SchematicFileNameMigration()
	);

	private Migrations() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> runAll());
	}

	/** Runs every migration, throwing {@link MigrationException} at the first that fails. */
	public static void runAll() {
		for (Migration migration : ALL) {
			try {
				migration.run();
			} catch (Exception e) {
				String message = "MCVCS could not migrate its files in " + BuildStorage.root().toAbsolutePath()
					+ " (" + migration.description() + "): " + e.getMessage()
					+ ". Fix or move the files named above and start the server again.";
				MCVCS.LOGGER.error(message, e);
				throw new MigrationException(message, e);
			}
		}
	}

	/** Thrown on server start when a migration fails, crashing the server with a message saying what went wrong. */
	public static final class MigrationException extends RuntimeException {
		MigrationException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
