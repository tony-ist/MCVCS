package tony.mcvcs.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tony.mcvcs.MCVCS;
import tony.mcvcs.build.Build;
import tony.mcvcs.build.BuildStorage;

/**
 * Renames the schematics older versions of the mod wrote as {@code <buildname>/v<N>.schem} to
 * {@code <buildname>/<buildname>-v<N>.schem}, the name {@link BuildStorage#schematicFile} gives them now.
 * Only folders with a {@link BuildStorage#BUILD_FILE} are touched. A schematic whose new name is taken already is
 * left alone and fails the migration, since one of the two files would otherwise be lost.
 */
final class SchematicFileNameMigration implements Migration {
	private static final Pattern OLD_NAME =
		Pattern.compile("v([1-9][0-9]*)\\." + Pattern.quote(BuildStorage.FORMAT.getPrimaryFileExtension()));

	@Override
	public String description() {
		return "renaming schematics from v<N>.schem to <buildname>-v<N>.schem";
	}

	@Override
	public void run() throws IOException {
		Path root = BuildStorage.root();
		if (!Files.isDirectory(root)) {
			return;
		}
		List<Path> folders;
		try (Stream<Path> children = Files.list(root)) {
			folders = children
				.filter(folder -> Files.isRegularFile(folder.resolve(BuildStorage.BUILD_FILE)))
				.filter(folder -> Build.isValidName(folder.getFileName().toString()))
				.sorted()
				.toList();
		}
		for (Path folder : folders) {
			migrate(folder.getFileName().toString(), folder);
		}
	}

	private static void migrate(String name, Path folder) throws IOException {
		List<Path> files;
		try (Stream<Path> children = Files.list(folder)) {
			files = children.filter(Files::isRegularFile).sorted().toList();
		}
		for (Path file : files) {
			Matcher matcher = OLD_NAME.matcher(file.getFileName().toString());
			if (!matcher.matches()) {
				continue;
			}
			int version;
			try {
				version = Integer.parseInt(matcher.group(1));
			} catch (NumberFormatException e) {
				throw new IOException("Version number of " + file + " is too large", e);
			}
			Path renamed = BuildStorage.schematicFile(name, version);
			if (Files.exists(renamed)) {
				throw new IOException("Cannot rename " + file + " to " + renamed + " because that file already exists");
			}
			try {
				// No REPLACE_EXISTING: a file appearing at the new name in the meantime fails the move instead of being overwritten.
				Files.move(file, renamed);
			} catch (IOException e) {
				throw new IOException("Cannot rename " + file + " to " + renamed + ": " + e, e);
			}
			MCVCS.LOGGER.info("Renamed schematic {} to {}", file, renamed.getFileName());
		}
	}
}
