# MCVCS

A version control system for redstone builds, as a Minecraft Fabric mod.

Select a build with WorldEdit, turn it into a MCVCS build, and commit snapshots of it as you work. Every commit is saved as a schematic, and any earlier version can be previewed in place, client-side, without touching the world.

The preferred way to use builds with this mod is to have them hover in the air, not touching the ground or anything else that is not part of them. `/vcs expand` grows a build's region until only air surrounds it, so it can pick up whatever you built out past the edges; a build standing on the ground would take the ground with it.

## Requirements

- Minecraft 26.1.2 with Fabric Loader and Fabric API
- WorldEdit (used for selections and for reading and writing schematics)
- Operator level 2 (cheats) to run the commands
- The mod can run on the server alone; players without it on their client can still join and use every command. Only previews, build labels and the selection box are rendered client-side, so those need the mod installed on the client too (see below)

## Server-side only or with the client

The mod works in two setups:

- **Server only.** Install it on the server (or in the host's single-player game). Players connect with a vanilla Fabric client and get the full command set: `create`, `select`, `builds`, `deselect`, `commit`, `load`, `diff`, `expand`, `checkout` and `delete` all run on the server. Nothing is drawn in their world, though: no build labels and no selection box, `/vcs diff` only reports its counts in chat, and `/vcs preview` refuses with a message saying the client does not have MCVCS installed.
- **Server and client.** Install it on both. On top of the commands, the client shows every build's name floating above its region, draws the selected build's bounding box, highlights the blocks `/vcs diff` finds, can render `/vcs preview <version>` in place of the real blocks, and adds a hotkey that selects the build under your crosshair (see below).

There is no client-only mode: the builds live on the server, so the mod has to be there for anything to work.

## Commands

| Command | What it does |
| --- | --- |
| `/vcs create <buildname>` | Turns the bounding box of your current WorldEdit selection into a build and saves it as version 1. Without a selection, it waits for you to click the build instead: punch any block of it, with anything or nothing in hand, or right-click one with an empty hand, and the build is grown from that block over everything connected to it, the way `/vcs expand` grows a region (so it should hover in the air, see above), then created and committed as version 1. The click neither breaks nor uses the block; the server puts it back if your client already did. WorldEdit tools go first: a wand or brush click does what it always does and the next click is waited for instead. If the connected blocks would reach past 5,000,000, nothing is created and you are asked to select the build with WorldEdit. Running `/vcs create` again, with or without a selection, replaces a click still being waited for. The name must not belong to an existing build, ignoring case (`Foo` and `foo` are the same build), and the region may not overlap an existing build in the same dimension. The build becomes your selected build for this world. |
| `/vcs select <buildname>` | Selects an existing build so its bounding box is shown and later commands act on it. |
| `/vcs builds` | Lists every build in this world with its latest version and size. Each one has a `[Select]` button in chat that runs `/vcs select` for it; the selected build is marked `[selected]` instead. |
| `/vcs deselect` | Clears your selected build: its bounding box, and any preview or diff highlighting of it, disappear and commands that need a selection refuse until you select one again. |
| `/vcs commit` | Saves the selected build's region again as the next version. The region is the one captured by `create`; your current WorldEdit selection is ignored. If anything other than air touches the region, the version is still saved but a yellow warning tells you to run `/vcs expand`, since the touching blocks were left out. |
| `/vcs preview <version>` | Renders that version in place of the real blocks inside the build's region. Nothing in the world changes. |
| `/vcs preview off` | Shows the real blocks again. |
| `/vcs load [version]` | Puts that version, or the latest one if none is given, into your WorldEdit clipboard, replacing whatever you had copied, so `//paste` places it. The origin is one block above the top north-west corner of the build, so `//paste` puts the build one block below your feet, extending east and south. Nothing is written to WorldEdit's own schematic folder. |
| `/vcs diff [version]` | Compares the blocks currently inside the build's region with that version, or with the version the region holds if none is given (the latest one, unless you checked out an earlier one), and reports how many were added (air in the version, a block now), removed (a block in the version, air now) or changed (a different block or block state). The client highlights them in place with see-through boxes: green for added, red for removed, yellow for changed. Block entity data counts too: a barrel, chest, furnace or any other container whose contents changed, or a sign whose text changed, is shown as changed even though the block itself is the same. Everything a block entity saves is compared, so a furnace that is smelting or a hopper passing items also differs from a saved version by its timers. |
| `/vcs diff off` | Removes the highlights. |
| `/vcs expand` | Grows the selected build's region until only air surrounds it, so whatever you built out past its edges is inside it again, and commits the grown region as the next version. Anything touching the region, even only at a corner, pulls it out to cover that block, and anything touching that pulls it further, which is why builds should hover in the air (see above). Refuses, and changes nothing, if the grown region would exceed 5,000,000 blocks or overlap another build. Versions committed before an expand keep their smaller size, but every schematic remembers where it was copied from, so `preview`, `diff` and `checkout` place them inside the grown region exactly where they were built and treat the rest of the region as air. |
| `/vcs checkout <version\|latest> [-f]` | Empties the build's region and puts that version, or the latest one, back into it, exactly where it was committed from: a version committed before an expand lands in its old place and the rest of the grown region stays empty. Every block is placed the way WorldEdit places them with `//perf off`, without lighting, neighbour or block updates, so redstone, observers, pistons and sand come back exactly as they were saved instead of reacting to the blocks appearing around them; only the clients are told. `//undo` reverts it. Refuses while the region has uncommitted changes, that is, differs by any block or block entity data from the version it holds: commit first (or `/vcs diff` to see the changes), or add `-f` to overwrite them; the message then says how many blocks were overwritten, and `//undo` brings them back. The checked-out version becomes the one the region holds, so you can check out another version straight after, and `/vcs commit` from there saves the region as the next version as usual. |
| `/vcs delete <buildname>` | Asks you to confirm deleting the build. Nothing is deleted until you run `/vcs confirmDelete`; the request is forgotten if you leave the server first, and a second `/vcs delete` replaces it. |
| `/vcs confirmDelete` | Deletes the build your last `/vcs delete` named: its folder with every version in it is removed and anyone who had it selected loses that selection, along with any preview or diff highlighting of it. This cannot be undone. |

## Hotkey

With the mod on your client, pressing `V` selects the build under your crosshair, the same as running `/vcs select` for it. It takes the nearest build whose region your line of sight passes through, or the build you are standing in.

The key can be rebound like any other under Options, Controls, Key Binds, in the MCVCS category.

## How it works

- Schematics are written in Sponge v3 format to the mod's own `mcvcs/` folder in the game directory (next to `config/`, `saves/` and so on), separate from WorldEdit's `//schem` files. Each build has a folder named after it holding one file per version: `mcvcs/<buildname>/v1.schem`, `mcvcs/<buildname>/v2.schem`, ...
- Build names become folder names, so they may only contain letters, digits, `_`, `+`, `-` and dots between those characters.
- Each build's folder also holds `build.json` describing it: its name, the world it belongs to (the save folder's name, e.g. `New World`, or `level-name` on a server), the dimension its box is in, the box itself, the latest version and, after a `/vcs checkout` of an earlier version, a `head` field naming the version the box holds (left out while that is the latest one). It is rewritten on every create, commit, expand and checkout, and the folder is the only place the build exists: nothing is stored in the world save, and deleting a build's folder, which is what `/vcs confirmDelete` does, removes it.
- Schematics are saved with the world position they were copied from (WorldEdit's `Origin` and `Offset` fields), which is how a version older than an expand finds its place inside the grown box.
- The `mcvcs/` folder is shared by every world opened from the same game directory, so commands only see builds whose `world` matches the one being played, and a build name, compared without regard to case, can only be used by one build at a time, in one world. Renaming a save folder orphans its builds until `world` in their `build.json` is updated to match.
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
gradlew.bat runClientGameTest -Pgametest=VcsCheckoutCommandGameTest  # run only that game test (comma-separate several)
gradlew.bat runClient          # run game client with this mod
```

`runClient` logs in as the offline player `Dev` (set in `build.gradle`) so the player's UUID, and with it the selection in `mcvcs/selections.json`, is the same on every launch; without it Minecraft picks a random `Player<n>` each time.

## License

CC0 1.0 Universal, see [LICENSE](LICENSE).

## TODO

- Add version tags. To keep track of TickNet versions more easily. Tags could be like '1.5.4' and selectable in checkouts, diffs and previews
- Aliases for commands to type them faster
- Change boundinx box color during preview and display version that is being previewed in the label
- Display builds and versions on the client in overlay. Also show rotating 3D render of the build. Make buttons in overlay to select, checkout, diff and preview builds.
- When modifying build, update diff in real time
- Disable sounds in test client so that I don't get jumpscared. Also make it creative mode and spanw player as flying not falling if he's in midair
- Render label of the build on the nearest edge to the player. This way labels of the big builds will be seen better.
- Bug with preview where I create ARPU, commit, delete bug chunk of it, commit, then preview whole version, it does not show chunk
- When making chekcouts, actions are stored in worldedit queue, so //undo undoes mcvcs actions. This is undesired, make mcvcs actions and worldedit actions always separate.
- Command to tp to a build
- Help for commands