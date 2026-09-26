package tony.mcvcs.migration;

import java.io.IOException;

/**
 * One step bringing what an older version of the mod left under {@code mcvcs/} up to what the current one reads.
 * A migration must be safe to run on files it has already migrated, or that never needed it, and do nothing to them:
 * every one is run on every server start, see {@link Migrations}.
 */
public interface Migration {
	/** What the migration changes, for the log and for the message a failure crashes the server with. */
	String description();

	/** Migrates whatever needs it, throwing if anything cannot be, which stops the server from starting. */
	void run() throws IOException;
}
