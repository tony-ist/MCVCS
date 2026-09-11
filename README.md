# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

Select a build with WorldEdit, turn it into a project, and commit snapshots of it as you work. Every commit is saved as a schematic, and any earlier version can be previewed in place, client-side, without touching the world.

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- WorldEdit (used for selections and schematic storage)
- Operator level 2 (cheats) to run the commands
- Previews need the mod installed on the client too; everything else works server-side only

## Commands

| Command | What it does |
| --- | --- |
| `/vcs create <buildname>` | Turns your current WorldEdit selection into a project and saves it as version 1. The project becomes your selected project for this world. |
| `/vcs select <buildname>` | Selects an existing project so its bounding box is shown and later commands act on it. |
| `/vcs commit` | Saves the selected project's region again as the next version. The region is the one captured by `create`; your current WorldEdit selection is ignored. |
| `/vcs preview <version>` | Renders that version in place of the real blocks inside the project's region. Nothing in the world changes. |
| `/vcs preview off` | Shows the real blocks again. |

## How it works

- Schematics are written in Sponge v3 format to WorldEdit's schematics directory (the same place `//schem save` uses), named `<buildname>` for version 1 and `<buildname>_v<N>` afterwards.
- Projects are tracked per world and per name. Selections are per player and per world, and the selected project's bounding box is synced to the client and drawn in the world.
- The project registry lives in memory and is cleared when the server stops.

## Development

```
gradlew.bat build              # build the mod
gradlew.bat runClientGameTest  # run game tests (produces screenshots)
gradlew.bat runClient          # run game client with this mod
```

## License

CC0 1.0 Universal, see [LICENSE](LICENSE).

## TODO

- Load projects when game launches and save them on game exit (and on some other actions too to not lose progress)
- Store schematics in a separate non-worldedit folder to separate them. Group them in folders by project names.
- `/vcs deselect`
- `/vcs checkout version` clears current selection and loads selected version instead. Think about what happens when build has observers or updating components.
- Diff tool that shows which block changed between current version of the build and selected version from VCS.
