# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

MCVCS saves versions of your redstone builds. Save a version whenever you like, see what you changed since, and load any older version back.

## Server and client

The mod works on the server alone: players without it can still join and use every command except `preview` and `changes` highlights. Without the mod on your client, `place` puts the copy down right away instead of showing a preview first.

With the mod on your client you also get outlines and names over your builds, previews, highlighted changes, and the builds screen (press `B`).

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- Cheats turned on (operator permission) to use the mod

## Quick start

1. Build something floating in the air.
2. Run `/vcs create mybuild` and hit any block of it.
3. Change a few blocks.
4. Run `/vcs changes` to see what you changed.
5. Run `/vcs save`.
6. Run `/vcs load 1` to go back to the first version.

Or press `B` and do the same with buttons.

## Commands

A **build** is something you built, with all its saved versions. A **copy** is one of its appearances in the world; a build can have several, and each can be changed, saved and loaded on its own. Most commands work on the build you **selected**.

Builds are shared by all single player worlds of one Minecraft instance: a build created in one world can be placed and saved from any other. Copies belong to the world they stand in.

### Builds

- `/vcs create <buildname> [copyname] [-b]`\
  Saves something you built as version 1 of a new build. Hit any block of it: everything touching that block is saved, so keep your build surrounded by air.
  - `-b`: hit two opposite corners instead; everything between them is saved.
- `/vcs cancelCreate`\
  Cancels `/vcs create`; no build is created.
- `/vcs builds`\
  Lists all builds and their copies in this world, with buttons to select or teleport to each one.
- `/vcs rename <buildname> <newname>`\
  Renames a build in all your worlds, keeping its versions, labels and copies.
- `/vcs delete <buildname> [-c]`\
  Deletes a build and all its saved versions, after you confirm with `/vcs confirmDelete`. Its blocks stay in the world. If the build is also used in other worlds, you are warned; its blocks there always stay.
  - `-c`: also clears its blocks from this world.
- `/vcs confirmDelete`\
  Confirms the deletion. This can't be undone.

### Selecting

- `/vcs select [buildname [copyname]]`\
  Selects the build the other commands work on. Without a name, hit a block of the build. You can also look at it and press `V`.
- `/vcs deselect`\
  Clears your selection.
- `/vcs tp [buildname [copyname]]`\
  Teleports you on top of a build, or of your selected one if you don't name any.

### Versions

- `/vcs save [label]`\
  Saves the selected build as its next version. Optionally gives the new version a label.
- `/vcs load <version|label|latest> [-f]`\
  Replaces the selected build with that version. Refuses if you have unsaved changes.
  - `-f`: deletes unsaved changes and any blocks in the way.
- `/vcs changes [version|label]`\
  Highlights what changed since that version, or since the version you last saved or loaded: green is added, red is removed, yellow is changed.
- `/vcs changes off`\
  Hides the highlights.
- `/vcs preview <version|label>`\
  Shows how that version looks right in place, without changing any blocks.
- `/vcs preview off`\
  Shows the real blocks again.
- `/vcs label <version|label> <label>`\
  Names a version, e.g. `/vcs label 2 with-roof`, so you can type `with-roof` instead of the version number in any command.
- `/vcs unlabel <label>`\
  Removes a label. The version itself stays.

### Copies

- `/vcs place <buildname> [version|label|latest] [copyname] [-f]`\
  Shows a copy of the build where you stand. Move it with the numpad keys or `Left Alt` + scroll, then press numpad `5` or run `/vcs confirmPlace` to place it.
  - `-f`: allows placing over existing blocks, overwriting them.
- `/vcs confirmPlace [-f]`\
  Places the copy and selects it.
  - `-f`: allows placing over existing blocks, overwriting them.
- `/vcs cancelPlace`\
  Removes the copy without placing it.
- `/vcs unplace [-k]`\
  Removes the selected copy and clears its blocks. Its saved versions stay. Asks you to confirm with `/vcs confirmUnplace` if there are unsaved changes.
  - `-k`: keeps the blocks in the world and only stops tracking them.
- `/vcs confirmUnplace`\
  Confirms the removal, deleting unsaved changes.

### Resizing

- `/vcs fit [label]`\
  Fits the selected build's box to the build inside it and saves it as a new version, with the label if given. Use it after you build past the box's edges or clear part of it. In the save window, tick **Fit bounding box** to do the same.
- `/vcs resize`\
  Resizes the selected build's box by hand. Hold `Left Alt` and scroll to move the side you look at, then press numpad `5` or run `/vcs confirmResize` to save it as a new version. The box turns red if it overlaps another copy. Needs the mod on your client.
- `/vcs cancelResize`\
  Stops resizing and leaves the box as it was.

### Help

- `/vcs help [command]`\
  Lists all commands, or shows the full help for one of them.
- `/vcs <command> -h`\
  Shows the full help for that command instead of running it.
- `/vcs version`\
  Shows the version of MCVCS the server runs.

## Hotkeys

With the mod on your client, pressing `V` **selects** the build you are looking at, the same as running `/vcs select` for it.

Pressing `B` opens the **builds screen**, and pressing it again closes it.

Holding left alt and scrolling **moves** the `/vcs place` preview around, or **moves** a side of the `/vcs resize` box. Pressing alt picks the direction the wheel works along, shown with arrows: through the side you look at, or, looking at none, the direction you look along most.

Every key can be **rebound** like any other under Options, Controls, Key Binds, in the MCVCS category.

## Client settings

What belongs to neither the key binds screen nor the server lives in `config/mcvcs.json`, written with its defaults the first time you run the mod. With [Mod Menu](https://modrinth.com/mod/modmenu) installed, its **Config** button opens a screen to change them in game; they are saved when the screen is closed.

```json
{
  "rotationSpeed": 36.0,
  "cellSize": 96,
  "autoDownloadLimit": 1000000,
  "showLabels": true,
  "showSelectionBox": true
}
```

| Setting | What it does |
| --- | --- |
| `rotationSpeed` | How fast the previews in the builds overlay turn, in degrees per second. 0 to 720, 36 by default, which is one turn every 10 seconds; 0 holds them still. |
| `cellSize` | Width and height of each preview in the builds overlay, in GUI pixels. 48 to 512, 96 by default. |
| `autoDownloadLimit` | The biggest build, in blocks of its box, whose preview the builds overlay downloads by itself. Bigger builds wait for a click. 1,000,000 by default; 0 makes every build wait. |
| `showLabels` | Whether builds near you have their names floating above them. `true` by default. |
| `showSelectionBox` | Whether the selected build is outlined. `true` by default. The green outline of a previewed version shows either way. |

The file is read again every time you join a world or server, so an edit takes hold without restarting the game. A file that cannot be read is logged and ignored, leaving the settings as they were. A file missing some of these settings, or holding ones older versions of the mod wrote, is written out again with exactly these settings, keeping the values it gave.

## How it works

- Schematics are written in Sponge v3 format, which WorldEdit and other tools can load, to the mod's own `mcvcs/` folder in the game directory (next to `config/`, `saves/` and so on). Only blocks and block entities are saved, not entities or biomes. Each build has a folder named after it holding one file per version: `mcvcs/<buildname>/<buildname>-v1.schem`, `mcvcs/<buildname>/<buildname>-v2.schem`.
- `build.json` contains information about bounding box for each version, and origin point for placements, grouped by world. A world is identified by its save folder name, so renaming the folder leaves its placements behind.
- Bottom north-west corner of the version 1 of the build is its bounding box's `[0, 0, 0]` coordinate, that is stored in `build.json`.
- Origin of the placement is coordinates of a point in the world which corresponds to `[0, 0, 0]` point of the build's version. For example, `main` placement with version 2 from example below will have origin `[100, 64, -30]` in the world. This origin will correspond to `[0, 0, 0]` point of version 2's bounding box.
- After `/vcs load` or `/vcs save`, the placement's `head` points to that version, so commands like `/vcs changes` compare against it. Head is also used to warn you before loading that you have unsaved changes.
- In code and in `build.json`, a copy is called a placement, saving is a commit, loading is a checkout, and a label is a tag.

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
  "version": 2,
  "versions": {
    "1": {"min": [0, 0, 0], "max": [7, 12, 7]},
    "2": {"min": [-1, 0, -1], "max": [8, 15, 8]}
  },
  "placements": {
    "New World": {
      "main":    {"dimension": "minecraft:overworld", "origin": [100, 64, -30], "head": 2},
      "testrig": {"dimension": "minecraft:overworld", "origin": [400, 70, 0], "head": 1}
    },
    "Creative test": {
      "main":    {"dimension": "minecraft:overworld", "origin": [0, 100, 0], "head": 1}
    }
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

- When placing too high, refuse instead of placing half

- Make movement of preview during placement more intuitive, maybe only move in direction you look, not using the plane.
- /vcs tag latest 1.0.0 should be possible and autocomplete latest. /vcs tag 1.0.0 should tag head by default
- Aliases for commands to type them faster
- New command /vcs move initiates moving preview for current placement allowing to change its position with numpad keys and press 5 moves it physically in the world
- Add hotkeys to select next and previous version preview (could be made obsolete by UI Overlay)
- Big diffs lag client too much (see it on mcinmc/p2)
- Make diffs more visible. Right now they intersect into clutter. 
- Ability to rotate during placement preview
- Confirm you want to resize when there are uncommited changes
- Removing/editing labels through overlay
- Add version to overlay

### Nice to have

- Archiving versions of builds so that they are not shown in lists
- When modifying build, update diff in real time
- Disable sounds in test client so that I don't get jumpscared. Also make it creative mode and spanw player as flying not falling if he's in midair
- Render label of the build on the nearest edge to the player. This way labels of the big builds will be seen better.
- /vcs off command to turn off diff and preview
- In tests display test name and sequential number and all number of tests running in chat or just screen.
- Renaming placements
- Split overlay tests into different game test files