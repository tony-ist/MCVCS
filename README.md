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

| Command | What it does |
| --- | --- |
| `/vcs create <buildname> [placementname] [-we]` | Waits for you to click the build, then creates it: punch any block of it, with anything or nothing in hand, or right-click one with an empty hand, and the build is grown from that block over everything connected to it, the way `/vcs fit` grows a box (so it should hover in the air, see above), then created and committed as version 1. The click neither breaks nor uses the block; the server puts it back if your client already did. WorldEdit tools go first: a wand or brush click does what it always does and the next click is waited for instead. If the connected blocks would reach past 5,000,000, nothing is created and you are asked to use `-we` instead. With `-we`, the bounding box of your current WorldEdit selection becomes the build and no click is waited for; without a selection the command refuses. Your selection is otherwise ignored. Running `/vcs create` again replaces a click still being waited for. The name must not belong to an existing build, ignoring case (`Foo` and `foo` are the same build), and the box may not overlap any placement in the same dimension. What is created is the build's first placement, called `main` unless you name it, and it becomes your selected placement for this world. |
| `/vcs place <buildname> [version\|tag\|latest] [placementname] [-f]` | Shows another copy of the build where you stand, without touching the world: that version, or the latest one if none is given, is drawn with its top north-west corner one block below your feet, so it hangs below you extending east and south, exactly where `/vcs load` and `//paste` would put it. Only you see it. Line it up with the numpad keys (see [Hotkeys](#hotkeys)) and run `/vcs confirmPlace` to place it, or `/vcs cancelPlace` to drop it; the copy is forgotten if you leave the server first, and a second `/vcs place` replaces it. Its box is green while it can be placed where it stands and red while it cannot, because it overlaps a placement or because blocks are standing in the way and no `-f` was given. The new placement is named `p2`, `p3` and so on unless you name it. Without the mod on your client there is nothing to draw the copy with, so it is placed where you stand straight away. |
| `/vcs confirmPlace [-f]` | Puts the copy your last `/vcs place` is showing into the world where you have moved it, as a placement of its own, and selects it. It lives its own life from then on: check it out and modify it on its own, and commits from it become versions of the same build. The blocks are placed without block updates and outside your WorldEdit history, the same way `/vcs checkout` places them. Refuses if the copy overlaps another placement, and refuses if anything is already standing where it goes unless you add `-f` here or gave it to `/vcs place`, which overwrites those blocks for good; a refused copy stays up, so you can move it somewhere clear and confirm again. |
| `/vcs cancelPlace` | Stops showing the copy your last `/vcs place` is showing. Nothing was ever put into the world, so nothing is taken back. |
| `/vcs unplace [-k]` | Takes your selected placement out of its build and empties its box, leaving the ground clear; the version it held is on disk, so nothing is lost and it happens at once. Add `-k` to leave its blocks standing as ordinary world blocks instead, removing only the tracking. If the box differs from the version it holds, that work would be lost, so you are asked to confirm first and nothing happens until you run `/vcs confirmUnplace`; the request is forgotten if you leave the server first. The build and every version of it stay on disk, so `/vcs place` can put it back. |
| `/vcs confirmUnplace` | Removes the placement your last `/vcs unplace` named, discarding the uncommitted changes it was holding: it stops being tracked and anyone who had it selected loses that selection. Its box is emptied unless `-k` was given, in which case its blocks are left standing where they are. |
| `/vcs select [buildname [placementname]]` | Selects the placement whose box is shown and that later commands act on. With no arguments it waits for you to punch a block of the placement you want, the same kind of click `/vcs create` waits for: the block is left alone and whichever placement covers it is selected. With a build that has one placement, that one is selected at once; with a build that has several, you are asked to punch one. With a placement name too, that placement is selected outright. |
| `/vcs builds` | Lists every build in this world with its latest version, and under it each of its placements, labelled `Placement`, with the version it holds, its size and where it stands. Each placement has a `[Select]` button in chat that runs `/vcs select` for it; the selected one is marked `[selected]` instead. Every placement, selected or not, also has a `[Tp]` button that runs `/vcs tp` to it. |
| `/vcs deselect` | Clears your selection: the bounding box, and any preview or diff highlighting, disappear and commands that need a selection refuse until you select one again. |
| `/vcs commit [tagname]` | Saves what is inside the selected placement's box as the build's next version. The box is the one that placement holds; your current WorldEdit selection is ignored. Every other placement of the build can then check that version out, wherever it stands; their own heads do not move. With a tag name, e.g. `/vcs commit 2.0.0`, the new version is tagged with it the way `/vcs tag` tags one; a tag the build already uses is refused and nothing is committed. If anything other than air touches the box, the version is still saved but a yellow warning tells you to run `/vcs fit`, since the touching blocks were left out. |
| `/vcs tag <version\|tag> <tagname>` | Tags that version of the selected placement's build, e.g. `/vcs tag 2 2.0.0` tags v2 as `2.0.0`. A tag may contain letters, digits, `-`, `_`, `+` and dots, but may not be digits alone, which would read as a version number. A version can have any number of tags, but a tag names one version of a build only, so a tag already in use is refused rather than moved. Every command that takes a version (`place`, `checkout`, `diff`, `preview`, `load` and `tag` itself) takes a tag in its place, e.g. `/vcs checkout 2.0.0`, and completes tags alongside the version numbers. Tags are shown after the version wherever it is reported, e.g. `v2 (2.0.0)` in `/vcs commit`, `/vcs checkout` and `/vcs builds`. |
| `/vcs untag <tagname>` | Removes that tag from the selected placement's build, e.g. `/vcs untag 2.0.0`, and completes the build's tags. The version it named stays, with any other tags it has, and the tag is free to be given to another version. |
| `/vcs preview <version\|tag>` | Renders that version in place of the real blocks inside the selected placement's box, where that placement holds it. Nothing in the world changes. A version bigger than the box, such as one from before `/vcs fit` shrank it, is drawn over both boxes together, hiding whatever else stands in the extra space, even another placement. |
| `/vcs preview off` | Shows the real blocks again. |
| `/vcs load [version\|tag]` | Puts that version, or the latest one if none is given, into your WorldEdit clipboard, replacing whatever you had copied, so `//paste` places it. The origin is one block above the top north-west corner of the build, so `//paste` puts the build one block below your feet, extending east and south. What you paste is only blocks; `/vcs place` is what adds a placement the mod keeps track of. Nothing is written to WorldEdit's own schematic folder. |
| `/vcs diff [version\|tag]` | Compares the blocks currently inside the selected placement's box with that version, or with the version the placement holds if none is given (the latest one, unless you checked out an earlier one), and reports how many were added (air in the version, a block now), removed (a block in the version, air now) or changed (a different block or block state). The client highlights them in place with see-through boxes: green for added, red for removed, yellow for changed. Block entity data counts too: a barrel, chest, furnace or any other container whose contents changed, or a sign whose text changed, is shown as changed even though the block itself is the same. Everything a block entity saves is compared, so a furnace that is smelting or a hopper passing items also differs from a saved version by its timers. |
| `/vcs diff off` | Removes the highlights. |
| `/vcs fit` | Fits the selected placement's box to the build inside it and saves the fitted box as the build's next version. The box grows until only air surrounds it, so whatever you built out past its edges is inside it again, and shrinks where its sides hold nothing but air, so it ends up the smallest box around the build. Anything touching the box, even only at a corner, pulls it out to cover that block, and anything touching that pulls it further, which is why builds should hover in the air (see above). Refuses, and changes nothing, if the box already fits, holds nothing but air, or the fitted box would exceed 5,000,000 blocks or overlap another placement. A box belongs to a version, not to a placement, so only the placement that was fitted changes size: the others keep the box of whatever version they hold until they check this one out. An earlier version bigger than the fitted box is still previewed and diffed whole, over both boxes together; in a diff, its blocks outside the box show as removed, or as changed where something else stands now. |
| `/vcs setSelection` | Gives your selected placement the bounding box of your WorldEdit selection as its box and saves what is inside it as the build's next version, like `/vcs fit` does with the box it finds. The new box can be bigger, smaller or somewhere else entirely; `/vcs weselect` first gives you the current box to adjust. Blocks of the old box outside the new one stay in the world but are no longer part of the build, and a yellow warning says how many. Refuses, and changes nothing, if you have no WorldEdit selection, the selection already is the box, or the new box would overlap another placement. You have to be in the placement's dimension. |
| `/vcs checkout <version\|tag\|latest> [-f]` | Empties the selected placement's box and puts that version, or the latest one, back into it, exactly where that placement holds it. The box becomes that version's size around the placement's origin, which never moves, so the build's own blocks stay where they are and only the edges of the box follow the version; a version committed before a fit lands in its old place and the rest stays empty. Every block is placed the way WorldEdit places them with `//perf off`, without lighting, neighbour or block updates, so redstone, observers, pistons and sand come back exactly as they were saved instead of reacting to the blocks appearing around them; only the clients are told. The checkout is kept out of your WorldEdit history: `//undo` never reverts it and only ever undoes your own WorldEdit edits. Refuses while the box has uncommitted changes, that is, differs by any block or block entity data from the version it holds: commit first (or `/vcs diff` to see the changes), or add `-f` to overwrite them. Refuses too when a bigger version would reach over blocks that are standing in the way, which `-f` also overwrites; a placement in the way is refused whatever you pass, since placements may never overlap. The message says how many blocks were overwritten; nothing brings them back. The checked-out version becomes the one the placement holds, so you can check out another version straight after, and `/vcs commit` from there saves the box as the build's next version as usual. Any preview or diff highlighting you had up is stopped, since both showed the box as it was before. |
| `/vcs delete <buildname> [-c]` | Asks you to confirm deleting the build. Nothing is deleted until you run `/vcs confirmDelete`; the request is forgotten if you leave the server first, and a second `/vcs delete` replaces it. The blocks of its placements are left standing in the world unless you add `-c`, which empties every one of their boxes as well. |
| `/vcs confirmDelete` | Deletes the build your last `/vcs delete` named: its folder with every version in it is removed and anyone who had one of its placements selected loses that selection, along with any preview or diff highlighting of it. This cannot be undone. |
| `/vcs tp [buildname [placementname]]` | Teleports you on top of that placement, or of the build's `main` one if you name only the build, or of your selected placement if you name nothing. |
| `/vcs weselect` | Sets your WorldEdit selection to the whole box of your selected placement, the corners as `//pos1` and `//pos2` would set them, so `//copy`, `//set` and the rest act on exactly that copy of the build. Moving the selection afterwards does not move the placement: its box only ever changes through `/vcs fit`, `/vcs setSelection` and `/vcs checkout`. WorldEdit keeps one selection per world, so you have to be in the placement's dimension; otherwise the command refuses and points you at `/vcs tp`. |
| `/vcs help [command]` | Without a command: tells how to start a build (run `/vcs create <buildname>`, then punch a block of it) and lists every command with a one-line summary. With one, e.g. `/vcs help commit`: that command's full help. `/vcs -h` is the same as `/vcs help`. |
| `/vcs <command> -h` | Shows that command's full help instead of running it, e.g. `/vcs checkout -h`. Works for every command above. |

## Hotkeys

With the mod on your client, pressing `V` selects the placement under your crosshair, the same as running `/vcs select` for it.

Pressing `B` opens the [builds overlay](#builds-overlay), and pressing it again closes it.

Holding left alt and scrolling mouse wheel moves the `/vcs place` preview around.

Every key can be rebound like any other under Options, Controls, Key Binds, in the MCVCS category.

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
