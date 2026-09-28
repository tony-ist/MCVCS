# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

MCVCS solves the problem of keeping track of your redstone creations and their versions. With MCVCS you can easily find the build you are searching for, checkout the required version, and also commit new changes while you are improving it.

## Server and client

The mod has a server-side and client-side parts. The mod can run on the server alone; players without it on their client can still join and use every command.

However, client-side mod improves experience a lot. It shows bounding boxes with labels for builds, previews, diffs, and adds overlay to manage your builds easily.

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- WorldEdit (used for selections and for reading and writing schematics)
- Operator level 2 (cheats) to run the commands

## Commands

A **build** is your creation with all its saved versions. A **placement** is a copy of a build standing in the world; a build can have several. Most commands act on your **selected** placement.

### Builds

- `/vcs create <buildname> [placementname] [-we]`\
  Creates a new build and its main placement from a group of blocks you punch. When you punch a block, the selection grows until there is only air around it, and this bounding box becomes your build v1. Keep your build hovering in the air so it doesn't grow into the ground.
  - `-we`: creates the build from your WorldEdit selection instead of a punch.
- `/vcs builds`\
  Lists all builds and their placements, with buttons to select or teleport to each one.
- `/vcs delete <buildname> [-c]`\
  Deletes a build with all its versions, after you confirm with `/vcs confirmDelete`. Its blocks stay in the world.
  - `-c`: also clears the blocks of all its placements.
- `/vcs confirmDelete`\
  Confirms the deletion. This cannot be undone.

### Selecting

- `/vcs select [buildname [placementname]]`\
  Selects the placement other commands will work on. Without arguments, punch a block of the placement you want. You can also look at it and press `V`.
- `/vcs deselect`\
  Clears your selection.
- `/vcs tp [buildname [placementname]]`\
  Teleports you to a placement, or to your selected one if you don't name any.
- `/vcs weselect`\
  Sets your WorldEdit selection to the selected placement's box, so you can use WorldEdit commands on it.

### Versions

- `/vcs commit [tagname]`\
  Saves the selected placement as the build's next version. Optionally gives the new version a tag.
- `/vcs checkout <version|tag|latest> [-f]`\
  Replaces the selected placement's blocks with that version. Refuses if you have uncommitted changes.
  - `-f`: overwrites uncommitted changes and any blocks in the way.
- `/vcs diff [version|tag]`\
  Highlights what changed compared to that version, or to the one you checked out: green is added, red is removed, yellow is changed.
- `/vcs diff off`\
  Removes the highlights.
- `/vcs preview <version|tag>`\
  Shows how that version looks right in place, without changing any blocks.
- `/vcs preview off`\
  Shows the real blocks again.
- `/vcs tag <version|tag> <tagname>`\
  Names a version, e.g. `/vcs tag 2 2.0.0`, so you can use `2.0.0` instead of the version number in any command.
- `/vcs untag <tagname>`\
  Removes a tag. The version itself stays.
- `/vcs load [version|tag]`\
  Copies that version, or the latest one, to your WorldEdit clipboard so you can `//paste` it.

### Placements

- `/vcs place <buildname> [version|tag|latest] [placementname] [-f]`\
  Shows a ghost copy of the build below your feet. Move it with the numpad keys or `Left Alt` + mouse wheel, then run `/vcs confirmPlace` (or press numpad `5`) to put it into the world. Each copy can hold its own version, and commits from it become versions of the same build.
  - `-f`: allows placing over existing blocks, overwriting them.
- `/vcs confirmPlace [-f]`\
  Puts the ghost copy into the world as a new placement and selects it.
  - `-f`: allows placing over existing blocks, overwriting them.
- `/vcs cancelPlace`\
  Removes the ghost copy.
- `/vcs unplace [-k]`\
  Removes the selected placement and clears its blocks. The build and its versions stay saved. Asks you to confirm with `/vcs confirmUnplace` if there are uncommitted changes.
  - `-k`: keeps the blocks in the world and only stops tracking them.
- `/vcs confirmUnplace`\
  Confirms the removal, discarding uncommitted changes.

### Resizing

- `/vcs fit`\
  Resizes the selected placement's box to fit the build inside it and saves it as a new version. Use it after you build past the box's edges or clear part of it.
- `/vcs setSelection`\
  Sets the selected placement's box to your WorldEdit selection and saves it as a new version.

### Help

- `/vcs help [command]`\
  Lists all commands, or shows the full help for one of them.
- `/vcs <command> -h`\
  Shows the full help for that command instead of running it.

## Hotkeys

With the mod on your client, pressing `V` **selects** the placement under your crosshair, the same as running `/vcs select` for it.

Pressing `B` opens the **builds overlay**, and pressing it again closes it.

Holding left alt and scrolling mouse wheel **moves** the `/vcs place` preview around.

Every key can be **rebound** like any other under Options, Controls, Key Binds, in the MCVCS category.

## Client settings

What belongs to neither the key binds screen nor the server lives in `config/mcvcs.json`, written with its defaults the first time you run the mod:

```json
{
  "rotationSpeed": 36.0,
  "cellSize": 96,
  "autoDownloadLimit": 1000000
}
```

| Setting | What it does |
| --- | --- |
| `rotationSpeed` | How fast the previews in the builds overlay turn, in degrees per second. 0 to 720, 36 by default, which is one turn every 10 seconds; 0 holds them still. |
| `cellSize` | Width and height of each preview in the builds overlay, in GUI pixels. 48 to 512, 96 by default. |
| `autoDownloadLimit` | The biggest build, in blocks of its box, whose preview the builds overlay downloads by itself. Bigger builds wait for a click. 1,000,000 by default; 0 makes every build wait. |

The file is read again every time you join a world or server, so an edit takes hold without restarting the game. A file that cannot be read is logged and ignored, leaving the settings as they were. A file missing some of these settings, or holding ones older versions of the mod wrote, is written out again with exactly these settings, keeping the values it gave.

## How it works

- Schematics are written in Sponge v3 format to the mod's own `mcvcs/` folder in the game directory (next to `config/`, `saves/` and so on), separate from WorldEdit's `//schem` files. Each build has a folder named after it holding one file per version: `mcvcs/<buildname>/<buildname>-v1.schem`, `mcvcs/<buildname>/<buildname>-v2.schem`.
- Each new version of a build is a `schem` file inside `<buildname>` folder.
- `build.json` contains information about bounding box for each version, and origin point for placements.
- Bottom north-west corner of the version 1 of the build is its bounding box's `[0, 0, 0]` coordinate, that is stored in `build.json`.
- Origin of the placement is coordinates of a point in the world which corresponds to `[0, 0, 0]` point of the build's version. For example, `testrig` with version 2 placement from example below will have origin `[400, 70, 0]` in the world. This origin will correspond to `[0, 0, 0]` point of version 2's bounding box.
- After checkout of any version, we move `head` pointer to that version. So now other commands like `diff` work against this version. Head is also used to warn you before checkouts that you have uncommited changes.

Example folder structure:

```
mcvcs/
  selections.json
  <buildname>/
    build.json
    <buildname>-v1.schem
    <buildname>-v2.schem
    ...
```

Example `build.json`:

```json
{
  "name": "tower",
  "world": "New World",
  "version": 2,
  "versions": {
    "1": {"min": [0, 0, 0], "max": [7, 12, 7]},
    "2": {"min": [-1, 0, -1], "max": [8, 15, 8]}
  },
  "placements": {
    "main":    {"dimension": "minecraft:overworld", "origin": [100, 64, -30], "head": 2},
    "testrig": {"dimension": "minecraft:overworld", "origin": [400, 70, 0], "head": 1}
  },
  "tags": {
    "1.0.0": 1,
    "2.0.0": 2
  }
}
```

## Development

```
gradlew.bat build              # build the mod
gradlew.bat runClientGameTest  # run game tests (produces screenshots)
gradlew.bat runClientGameTest -Pgametest=VcsPlaceCommandGameTest  # run only that game test (comma-separate several)
gradlew.bat runClient          # run game client with this mod
```

`runClient` logs in as the offline player `Dev` (set in `build.gradle`) so the player's UUID, and with it the selection in `mcvcs/selections.json`, is the same on every launch; without it Minecraft picks a random `Player<n>` each time.

## License

CC0 1.0 Universal, see [LICENSE](LICENSE).

## TODO

### Roadmap

- Improve UI Overlay to include versions and placements
- Aliases for commands to type them faster
- Make automatic releases on github by reading tags
- Command /vcs move initiates moving preview for current placement allowing to change its position with numpad keys and press 5 moves it physically in the world
- Submodules for build. One build can have submodules inside its box. Each submodule is itself a build. When we place parent build, all submodule placements appear inside. Bounding box for parent build includes all bounding boxes for submodules. Think about what happens when submodule and parent intersect not fullly.
- Add hotkeys to select next and previous version preview (could be made obsolete by UI Overlay)

### Nice to have

- When modifying build, update diff in real time
- Disable sounds in test client so that I don't get jumpscared. Also make it creative mode and spanw player as flying not falling if he's in midair
- Render label of the build on the nearest edge to the player. This way labels of the big builds will be seen better.
- Changing selection should stop preview and diff
- /vcs off command to turn off diff and preview
- In tests display test name and sequential number and all number of tests running in chat or just just screen.
- Renaming builds and placements
