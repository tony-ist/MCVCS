# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

Punch a block of a build to turn it into a MCVCS build, and commit snapshots of it as you work. Every commit is saved as a schematic, and any earlier version can be previewed in place, client-side, without touching the world.

The preferred way to use builds with this mod is to have them hover in the air, not touching the ground or anything else that is not part of them. `/vcs expand` grows a build's region until only air surrounds it, so it can pick up whatever you built out past the edges; a build standing on the ground would take the ground with it.

## Placements

A build can stand in the world more than once. Each copy of it is a **placement** with a name of its own: `/vcs create` makes the first one, called `main`, and `/vcs place <buildname>` drops another one where you stand. A placement is written as `buildname/placementname`, which is what its label above the world says and what the commands call it.

Every placement lives its own life. It holds a version of its own, you modify it on your own, and `/vcs checkout` moves only that placement to another version. Versions, though, belong to the build: a `/vcs commit` from any placement saves the build's next version, and every other placement can then check that version out. So you can keep `main` on v4 while a second placement tries out v5, then check v5 out at `main` when it works.

Commands act on the placement you have selected, not on the build. `/vcs select` takes a build with one placement straight away; when a build has several, it asks you to punch a block of the one you mean, and `/vcs select <buildname> <placementname>` names it outright.

Placements may never overlap, one another's or another build's, whichever build they belong to: every block in the world belongs to at most one of them.

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- WorldEdit (used for selections and for reading and writing schematics)
- Operator level 2 (cheats) to run the commands
- The mod can run on the server alone; players without it on their client can still join and use every command. Only previews, placement labels and the selection box are rendered client-side, so those need the mod installed on the client too (see below)

## Server-side only or with the client

The mod works in two setups:

- **Server only.** Install it on the server (or in the host's single-player game). Players connect with a vanilla Fabric client and get the full command set: `create`, `place`, `unplace`, `select`, `builds`, `deselect`, `commit`, `load`, `diff`, `expand`, `checkout`, `delete`, `tp`, `weselect` and `help` all run on the server. Nothing is drawn in their world, though: no placement labels and no selection box, `/vcs diff` only reports its counts in chat, and `/vcs preview` refuses with a message saying the client does not have MCVCS installed. `/vcs place` has nothing to show the copy with either, so it puts it into the world where you stand straight away, with no lining up and nothing to confirm.
- **Server and client.** Install it on both. On top of the commands, the client shows every placement's name floating above its box, draws the selected placement's bounding box, highlights the blocks `/vcs diff` finds, can render `/vcs preview <version>` in place of the real blocks, shows the copy `/vcs place` is about to put down so it can be lined up before it is confirmed, and adds hotkeys that select the placement under your crosshair and move that copy about (see below).

There is no client-only mode: the builds live on the server, so the mod has to be there for anything to work.

## Commands

| Command | What it does |
| --- | --- |
| `/vcs create <buildname> [placementname] [-we]` | Waits for you to click the build, then creates it: punch any block of it, with anything or nothing in hand, or right-click one with an empty hand, and the build is grown from that block over everything connected to it, the way `/vcs expand` grows a box (so it should hover in the air, see above), then created and committed as version 1. The click neither breaks nor uses the block; the server puts it back if your client already did. WorldEdit tools go first: a wand or brush click does what it always does and the next click is waited for instead. If the connected blocks would reach past 5,000,000, nothing is created and you are asked to use `-we` instead. With `-we`, the bounding box of your current WorldEdit selection becomes the build and no click is waited for; without a selection the command refuses. Your selection is otherwise ignored. Running `/vcs create` again replaces a click still being waited for. The name must not belong to an existing build, ignoring case (`Foo` and `foo` are the same build), and the box may not overlap any placement in the same dimension. What is created is the build's first placement, called `main` unless you name it, and it becomes your selected placement for this world. |
| `/vcs place <buildname> [version\|latest] [placementname] [-f]` | Shows another copy of the build where you stand, without touching the world: that version, or the latest one if none is given, is drawn with its top north-west corner one block below your feet, so it hangs below you extending east and south, exactly where `/vcs load` and `//paste` would put it. Only you see it. Line it up with the numpad keys (see [Hotkeys](#hotkeys)) and run `/vcs confirmPlace` to place it, or `/vcs cancelPlace` to drop it; the copy is forgotten if you leave the server first, and a second `/vcs place` replaces it. Its box is green while it can be placed where it stands and red while it cannot, because it overlaps a placement or because blocks are standing in the way and no `-f` was given. The new placement is named `p2`, `p3` and so on unless you name it. Without the mod on your client there is nothing to draw the copy with, so it is placed where you stand straight away. |
| `/vcs confirmPlace [-f]` | Puts the copy your last `/vcs place` is showing into the world where you have moved it, as a placement of its own, and selects it. It lives its own life from then on: check it out and modify it on its own, and commits from it become versions of the same build. The blocks are placed without block updates and outside your WorldEdit history, the same way `/vcs checkout` places them. Refuses if the copy overlaps another placement, and refuses if anything is already standing where it goes unless you add `-f` here or gave it to `/vcs place`, which overwrites those blocks for good; a refused copy stays up, so you can move it somewhere clear and confirm again. |
| `/vcs cancelPlace` | Stops showing the copy your last `/vcs place` is showing. Nothing was ever put into the world, so nothing is taken back. |
| `/vcs unplace [-k]` | Takes your selected placement out of its build and empties its box, leaving the ground clear; the version it held is on disk, so nothing is lost and it happens at once. Add `-k` to leave its blocks standing as ordinary world blocks instead, removing only the tracking. If the box differs from the version it holds, that work would be lost, so you are asked to confirm first and nothing happens until you run `/vcs confirmUnplace`; the request is forgotten if you leave the server first. The build and every version of it stay on disk, so `/vcs place` can put it back. |
| `/vcs confirmUnplace` | Removes the placement your last `/vcs unplace` named, discarding the uncommitted changes it was holding: it stops being tracked and anyone who had it selected loses that selection. Its box is emptied unless `-k` was given, in which case its blocks are left standing where they are. |
| `/vcs select [buildname [placementname]]` | Selects the placement whose box is shown and that later commands act on. With no arguments it waits for you to punch a block of the placement you want, the same kind of click `/vcs create` waits for: the block is left alone and whichever placement covers it is selected. With a build that has one placement, that one is selected at once; with a build that has several, you are asked to punch one. With a placement name too, that placement is selected outright. |
| `/vcs builds` | Lists every build in this world with its latest version, and under it each of its placements with the version it holds, its size and where it stands. Each placement has a `[Select]` button in chat that runs `/vcs select` for it; the selected one is marked `[selected]` instead. |
| `/vcs deselect` | Clears your selection: the bounding box, and any preview or diff highlighting, disappear and commands that need a selection refuse until you select one again. |
| `/vcs commit` | Saves what is inside the selected placement's box as the build's next version. The box is the one that placement holds; your current WorldEdit selection is ignored. Every other placement of the build can then check that version out, wherever it stands; their own heads do not move. If anything other than air touches the box, the version is still saved but a yellow warning tells you to run `/vcs expand`, since the touching blocks were left out. |
| `/vcs preview <version>` | Renders that version in place of the real blocks inside the selected placement's box, where that placement holds it. Nothing in the world changes. A version too big for the box is refused; check it out to see it at its own size. |
| `/vcs preview off` | Shows the real blocks again. |
| `/vcs load [version]` | Puts that version, or the latest one if none is given, into your WorldEdit clipboard, replacing whatever you had copied, so `//paste` places it. The origin is one block above the top north-west corner of the build, so `//paste` puts the build one block below your feet, extending east and south. What you paste is only blocks; `/vcs place` is what adds a placement the mod keeps track of. Nothing is written to WorldEdit's own schematic folder. |
| `/vcs diff [version]` | Compares the blocks currently inside the selected placement's box with that version, or with the version the placement holds if none is given (the latest one, unless you checked out an earlier one), and reports how many were added (air in the version, a block now), removed (a block in the version, air now) or changed (a different block or block state). The client highlights them in place with see-through boxes: green for added, red for removed, yellow for changed. Block entity data counts too: a barrel, chest, furnace or any other container whose contents changed, or a sign whose text changed, is shown as changed even though the block itself is the same. Everything a block entity saves is compared, so a furnace that is smelting or a hopper passing items also differs from a saved version by its timers. |
| `/vcs diff off` | Removes the highlights. |
| `/vcs expand` | Grows the selected placement's box until only air surrounds it, so whatever you built out past its edges is inside it again, and saves the grown box as the build's next version. Anything touching the box, even only at a corner, pulls it out to cover that block, and anything touching that pulls it further, which is why builds should hover in the air (see above). Refuses, and changes nothing, if the grown box would exceed 5,000,000 blocks or overlap another placement. A box belongs to a version, not to a placement, so only the placement that expanded grows: the others keep the box of whatever version they hold until they check this one out. |
| `/vcs checkout <version\|latest> [-f]` | Empties the selected placement's box and puts that version, or the latest one, back into it, exactly where that placement holds it. The box becomes that version's size around the placement's origin, which never moves, so the build's own blocks stay where they are and only the edges of the box follow the version; a version committed before an expand lands in its old place and the rest stays empty. Every block is placed the way WorldEdit places them with `//perf off`, without lighting, neighbour or block updates, so redstone, observers, pistons and sand come back exactly as they were saved instead of reacting to the blocks appearing around them; only the clients are told. The checkout is kept out of your WorldEdit history: `//undo` never reverts it and only ever undoes your own WorldEdit edits. Refuses while the box has uncommitted changes, that is, differs by any block or block entity data from the version it holds: commit first (or `/vcs diff` to see the changes), or add `-f` to overwrite them. Refuses too when a bigger version would reach over blocks that are standing in the way, which `-f` also overwrites; a placement in the way is refused whatever you pass, since placements may never overlap. The message says how many blocks were overwritten; nothing brings them back. The checked-out version becomes the one the placement holds, so you can check out another version straight after, and `/vcs commit` from there saves the box as the build's next version as usual. Any preview or diff highlighting you had up is stopped, since both showed the box as it was before. |
| `/vcs delete <buildname> [-c]` | Asks you to confirm deleting the build. Nothing is deleted until you run `/vcs confirmDelete`; the request is forgotten if you leave the server first, and a second `/vcs delete` replaces it. The blocks of its placements are left standing in the world unless you add `-c`, which empties every one of their boxes as well. |
| `/vcs confirmDelete` | Deletes the build your last `/vcs delete` named: its folder with every version in it is removed and anyone who had one of its placements selected loses that selection, along with any preview or diff highlighting of it. This cannot be undone. |
| `/vcs tp [buildname [placementname]]` | Teleports you on top of that placement, or of the build's `main` one if you name only the build, or of your selected placement if you name nothing. |
| `/vcs weselect` | Sets your WorldEdit selection to the whole box of your selected placement, the corners as `//pos1` and `//pos2` would set them, so `//copy`, `//set` and the rest act on exactly that copy of the build. Moving the selection afterwards does not move the placement: its box only ever changes through `/vcs expand` and `/vcs checkout`. WorldEdit keeps one selection per world, so you have to be in the placement's dimension; otherwise the command refuses and points you at `/vcs tp`. |
| `/vcs help [command]` | Without a command: tells how to start a build (run `/vcs create <buildname>`, then punch a block of it) and lists every command with a one-line summary. With one, e.g. `/vcs help commit`: that command's full help. `/vcs -h` is the same as `/vcs help`. |
| `/vcs <command> -h` | Shows that command's full help instead of running it, e.g. `/vcs checkout -h`. Works for every command above. |

## Hotkeys

With the mod on your client, pressing `V` selects the placement under your crosshair, the same as running `/vcs select` for it. It takes the nearest placement whose box your line of sight passes through, or the one you are standing in.

The numpad moves the copy `/vcs place` is showing, and does nothing while none is being shown:

| Key | What it does |
| --- | --- |
| `8` / `2` | Pushes the copy away from you and pulls it back, through the side of its box you are looking at |
| `4` / `6` | Slides it left and right along that side, as you see it, without changing its height |
| `7` / `9` | Raises and lowers it |
| `5` | Places it where it stands, the same as `/vcs confirmPlace`; where it cannot be placed it says why instead |

Holding left alt and turning the mouse wheel does the same as `8` and `2`: a notch up pushes the copy away from you, a notch down pulls it back. Unlike those keys the wheel goes by any of the six faces, so looking down on the copy from above a notch up lowers it and a notch down raises it, and looking up at it from below it is the other way round. The wheel changes your held item as usual the rest of the time, so it is only taken over while that key is held and a copy is being shown.

Which way the copy goes is read off the box, not off the compass: looking at its north side, `8` pushes it north to south, and looking at its east side, `8` pushes it east to west. Looking at the top or the bottom of the box, or away from it altogether, leaves `8`, `2`, `4` and `6` nothing to go by, so they move nothing and tell you to look at a side of it; the wheel only needs some face of the box in sight. `7`, `9` and `5` need no side and always work. Each press, and each notch of the wheel, moves one block.

Every key can be rebound like any other under Options, Controls, Key Binds, in the MCVCS category.

## Client settings

What belongs to neither the key binds screen nor the server lives in `config/mcvcs.json`, written with its defaults the first time you run the mod:

```json
{
  "sprintStep": 10
}
```

| Setting | What it does |
| --- | --- |
| `sprintStep` | Nothing at present. It used to be how many blocks a numpad press moved a `/vcs place` copy while the sprint key was held, which every press and wheel notch now moves one of; the setting is still read and written so it is there to build on. 1 to 1000, 10 by default. |

The file is read again every time you join a world or server, so an edit takes hold without restarting the game. A file that cannot be read is logged and ignored, leaving the settings as they were.

## How it works

- Schematics are written in Sponge v3 format to the mod's own `mcvcs/` folder in the game directory (next to `config/`, `saves/` and so on), separate from WorldEdit's `//schem` files. Each build has a folder named after it holding one file per version: `mcvcs/<buildname>/v1.schem`, `mcvcs/<buildname>/v2.schem`, ...
- Build and placement names become folder names and chat labels, so they may only contain letters, digits, `_`, `+`, `-` and dots between those characters.
- Geometry is kept in *build space*, the build's own coordinates, in which version 1's minimum corner is `(0, 0, 0)`. Every version has its own extent there, which is how versions of different sizes line up with each other, and every placement has an `origin`: the world position build space `(0, 0, 0)` sits at. A placement's box is the extent of the version it holds, laid at that origin. The origin is fixed when the placement is made and never moves again, so checking out a version of another size grows or shrinks the box around the build instead of sliding the build sideways.
- Each build's folder holds `build.json` describing it: its name, the world it belongs to (the save folder's name, e.g. `New World`, or `level-name` on a server), its latest version, the extent of every version and every placement of it with its dimension, origin and `head`, the version that placement holds. It is rewritten on every create, place, commit, expand, checkout and unplace, and the folder is the only place the build exists: nothing is stored in the world save, and deleting a build's folder, which is what `/vcs confirmDelete` does, removes it.

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
  }
}
```

- Schematics are still saved with the world position they were copied from (WorldEdit's `Origin` and `Offset` fields), which is what `/vcs load` and `//paste` go by, but those are the coordinates of the placement the version happened to be committed from and say nothing about where it belongs at another one. Where a version goes is worked out from `build.json` alone.
- The `mcvcs/` folder is shared by every world opened from the same game directory, so commands only see builds whose `world` matches the one being played, and a build name, compared without regard to case, can only be used by one build at a time, in one world. Renaming a save folder orphans its builds until `world` in their `build.json` is updated to match.
- Selections are per world and per player, stored in `mcvcs/selections.json` keyed by world then player UUID, each naming a build and one of its placements, so they are back after a restart.
- Every placement in the world, and which one you have selected, is synced to your client on join and whenever any of it changes. Each placement's `buildname/placementname` floats above its box while you are within 32 blocks of it, selected or not, and the selected placement's bounding box is drawn in its dimension.
- A `build.json` written before placements existed, which had one `box` and one `head` for the whole build, is converted the first time it is read: every version's extent is taken from its schematic and the box becomes a placement called `main`. The conversion lives in `LegacyBuild` on its own and can be deleted once no such file is left.

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
gradlew.bat runClientGameTest -Pgametest=VcsPlaceCommandGameTest  # run only that game test (comma-separate several)
gradlew.bat runClient          # run game client with this mod
```

`runClient` logs in as the offline player `Dev` (set in `build.gradle`) so the player's UUID, and with it the selection in `mcvcs/selections.json`, is the same on every launch; without it Minecraft picks a random `Player<n>` each time.

## License

CC0 1.0 Universal, see [LICENSE](LICENSE).

## TODO


### Roadmap

- A command to move a placement without unplacing and placing it again.
- Add version tags. To keep track of TickNet versions more easily. Tags could be like '1.5.4' and selectable in checkouts, diffs and previews
- Aliases for commands to type them faster
- Display builds and versions on the client in overlay. Also show rotating 3D render of the build. Make buttons in overlay to select, checkout, diff and preview builds.
- Make automatic releases on github by reading tags
- Make version automatically in format mcvcs-fabric-1.2.0+mc26.1.2
- Add shrink command to shrink selection to bounding box
- Deny naming builds starting from minus (-)
- vcs select, vcs select buildname should disarm punch
- In mcvcs folder name builds as buildname-v2 instead of just v2
- Allow change build box to new worldedit selection, think about shrinking when blocks get excluded
- Press numpad 5 to place the build during preview
- Sprint key should move placement preview it 10 blocks.
- Command /vcs move initiates moving preview for current placement allowing to change its position and press 5 moves it physically in the world

### Nice to have

- When modifying build, update diff in real time
- Disable sounds in test client so that I don't get jumpscared. Also make it creative mode and spanw player as flying not falling if he's in midair
- Render label of the build on the nearest edge to the player. This way labels of the big builds will be seen better.
- Changing selection should stop preview and diff
- /vcs off command to turn off diff and preview
- Add clickable tp command in /vcs builds list
- Add some effect when punching is armed
- In vcs builds display label Placement before placement name
