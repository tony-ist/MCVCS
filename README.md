# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

Select a build with WorldEdit, turn it into a project, and commit snapshots of it as you work. Every commit is saved as a schematic, and any earlier version can be previewed in place, client-side, without touching the world.

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- WorldEdit (used for selections and for reading and writing schematics)
- Operator level 2 (cheats) to run the commands
- Previews need the mod installed on the client too; everything else works server-side only

## Commands

| Command | What it does |
| --- | --- |
| `/vcs create <buildname>` | Turns the bounding box of your current WorldEdit selection into a project and saves it as version 1. The project becomes your selected project for this world. |
| `/vcs select <buildname>` | Selects an existing project so its bounding box is shown and later commands act on it. |
| `/vcs deselect` | Clears your selected project: its bounding box disappears and commands that need a selection refuse until you select one again. |
| `/vcs commit` | Saves the selected project's region again as the next version. The region is the one captured by `create`; your current WorldEdit selection is ignored. |
| `/vcs preview <version>` | Renders that version in place of the real blocks inside the project's region. Nothing in the world changes. |
| `/vcs preview off` | Shows the real blocks again. |

## How it works

- Schematics are written in Sponge v3 format to the mod's own `mcvcs/` folder in the game directory (next to `config/`, `saves/` and so on), separate from WorldEdit's `//schem` files. Each project has a folder named after it holding one file per version: `mcvcs/<buildname>/v1.schem`, `mcvcs/<buildname>/v2.schem`, ...
- Build names become folder names, so they may only contain letters, digits, `_`, `+`, `-` and dots between those characters.
- Each project's folder also holds `project.json` describing it: its name, the world it belongs to (the save folder's name, e.g. `New World`, or `level-name` on a server), the dimension its box is in, the box itself and the latest version. It is rewritten on every create and commit, and the folder is the only place the project exists: nothing is stored in the world save, and deleting a project's folder removes it.
- The `mcvcs/` folder is shared by every world opened from the same game directory, so commands only see projects whose `world` matches the one being played, and a build name can only be used by one world at a time. Renaming a save folder orphans its projects until `world` in their `project.json` is updated to match.
- Selections are per world and per player, stored in `mcvcs/selections.json` keyed by world then player UUID, so they are back after a restart. The selected project's bounding box is synced to the client and drawn in its dimension.

```
mcvcs/
  selections.json
  <buildname>/
    project.json
    v1.schem
    v2.schem
    ...
```

## Development

```
gradlew.bat build              # build the mod
gradlew.bat runClientGameTest  # run game tests (produces screenshots)
gradlew.bat runClient          # run game client with this mod
```

## License

CC0 1.0 Universal, see [LICENSE](LICENSE).

## TODO

- `/vcs checkout version` clears current selection and loads selected version instead. Think about what happens when build has observers or updating components.
- Diff tool that shows which block changed between current version of the build and selected version from VCS.
- Aliases for commands to type them faster
