# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

Select a build with WorldEdit, turn it into a build, and commit snapshots of it as you work. Every commit is saved as a schematic, and any earlier version can be previewed in place, client-side, without touching the world.

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- WorldEdit (used for selections and for reading and writing schematics)
- Operator level 2 (cheats) to run the commands
- Previews, build labels and the selection box need the mod installed on the client too; everything else works server-side only

## Commands

| Command | What it does |
| --- | --- |
| `/vcs create <buildname>` | Turns the bounding box of your current WorldEdit selection into a build and saves it as version 1. The build becomes your selected build for this world. |
| `/vcs select <buildname>` | Selects an existing build so its bounding box is shown and later commands act on it. |
| `/vcs builds` | Lists every build in this world with its latest version and size. Each one has a `[Select]` button in chat that runs `/vcs select` for it; the selected build is marked `[selected]` instead. |
| `/vcs deselect` | Clears your selected build: its bounding box disappears and commands that need a selection refuse until you select one again. |
| `/vcs commit` | Saves the selected build's region again as the next version. The region is the one captured by `create`; your current WorldEdit selection is ignored. |
| `/vcs preview <version>` | Renders that version in place of the real blocks inside the build's region. Nothing in the world changes. |
| `/vcs preview off` | Shows the real blocks again. |
| `/vcs load [version]` | Puts that version, or the latest one if none is given, into your WorldEdit clipboard, replacing whatever you had copied, so `//paste` places it. The origin is one block above the top north-west corner of the build, so `//paste` puts the build one block below your feet, extending east and south. Nothing is written to WorldEdit's own schematic folder. |

## How it works

- Schematics are written in Sponge v3 format to the mod's own `mcvcs/` folder in the game directory (next to `config/`, `saves/` and so on), separate from WorldEdit's `//schem` files. Each build has a folder named after it holding one file per version: `mcvcs/<buildname>/v1.schem`, `mcvcs/<buildname>/v2.schem`, ...
- Build names become folder names, so they may only contain letters, digits, `_`, `+`, `-` and dots between those characters.
- Each build's folder also holds `build.json` describing it: its name, the world it belongs to (the save folder's name, e.g. `New World`, or `level-name` on a server), the dimension its box is in, the box itself and the latest version. It is rewritten on every create and commit, and the folder is the only place the build exists: nothing is stored in the world save, and deleting a build's folder removes it.
- The `mcvcs/` folder is shared by every world opened from the same game directory, so commands only see builds whose `world` matches the one being played, and a build name can only be used by one world at a time. Renaming a save folder orphans its builds until `world` in their `build.json` is updated to match.
- Selections are per world and per player, stored in `mcvcs/selections.json` keyed by world then player UUID, so they are back after a restart.
- Every build in the world, and which one you have selected, is synced to your client on join and whenever any of it changes. Each build's name floats above its region while you are within 32 blocks of it, selected or not, and the selected build's bounding box is drawn in its dimension.

```
mcvcs/
  selections.json
  <buildname>/
    build.json
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

`runClient` logs in as the offline player `Dev` (set in `build.gradle`) so the player's UUID, and with it the selection in `mcvcs/selections.json`, is the same on every launch; without it Minecraft picks a random `Player<n>` each time.

## License

CC0 1.0 Universal, see [LICENSE](LICENSE).

## TODO

- `/vcs checkout version` clears current selection and loads selected version instead. Think about what happens when build has observers or updating components.
- Test that server mod works separately from client mod, and you can connect to server without client mod.
- Diff tool that shows which block changed between current version of the build and selected version from VCS.
- Aliases for commands to type them faster