package tony.mcvcs.project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import tony.mcvcs.MCVCS;

/**
 * The projects of one world and which one each player has selected there, stored with that world's save data.
 * <p>
 * Each dimension has its own instance, read from {@code data/mcvcs/projects.dat} under the dimension's save
 * directory the first time it is asked for and written back whenever the world saves. Every change marks the data
 * dirty, and {@link ProjectRegistry} additionally schedules a write straight away so a crash before the next
 * autosave does not lose a commit. Only the server thread touches it.
 */
public final class ProjectData extends SavedData {
	private static final Codec<ProjectData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Project.CODEC.listOf().fieldOf("projects").forGetter(data -> List.copyOf(data.projects.values())),
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).fieldOf("selected").forGetter(data -> data.selected)
	).apply(instance, ProjectData::new));
	// No data fixer: the format is this mod's own. Fabric API lets the fixer type be null.
	public static final SavedDataType<ProjectData> TYPE = new SavedDataType<>(MCVCS.id("projects"), ProjectData::new, CODEC, null);

	/** Every project in this world, by name. */
	private final Map<String, Project> projects = new HashMap<>();
	/** Name of the project each player has selected in this world. */
	private final Map<UUID, String> selected = new HashMap<>();

	private ProjectData() {
	}

	private ProjectData(List<Project> projects, Map<UUID, String> selected) {
		for (Project project : projects) {
			this.projects.put(project.name(), project);
		}
		this.selected.putAll(selected);
	}

	/** The data for {@code level}, loaded from disk on first use. */
	public static ProjectData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Records {@code project} as the current state of the build with its name. */
	public void put(Project project) {
		projects.put(project.name(), project);
		setDirty();
	}

	/** Makes the project called {@code name}, which must exist, {@code player}'s selected project. */
	public void select(UUID player, String name) {
		if (!projects.containsKey(name)) {
			throw new IllegalArgumentException("No project named '" + name + "'");
		}
		selected.put(player, name);
		setDirty();
	}

	/** The project {@code player} has selected here, if any. */
	public Optional<Project> selected(UUID player) {
		return Optional.ofNullable(selected.get(player)).flatMap(this::find);
	}

	/** The project called {@code name}, if it exists. */
	public Optional<Project> find(String name) {
		return Optional.ofNullable(projects.get(name));
	}

	/** Names of all projects, sorted. */
	public List<String> names() {
		return projects.keySet().stream().sorted().toList();
	}
}
