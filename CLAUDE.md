- You are on Windows. Important: use only PowerShell, DO NOT use Bash. Bash cannot run .\gradlew.bat properly, it gets frozen and waits forever for it to finish.
- Use `gradlew.bat` to run gradle commands.
- Use `runClientGameTest` from `gradlew.bat` to run game tests and make screenshots, look at the resulting screenshots to verify your changes.
- To run a single game test: `gradlew.bat runClientGameTest -Pgametest=<SimpleClassName>` (e.g. `-Pgametest=VcsCheckoutCommandGameTest`; comma-separate several). Tests live in `src/gametest/java/tony/mcvcs/gametest/` and extend `VcsGameTest`.
- While developing a feature, run only the game test for that feature (it saves a lot of time not running all others). Before finishing your work, run all the related tests using `gradlew.bat runClientGameTest` with filter to make sure nothing else broke. Avoid running all tests to save time.
    Example:
    ```
    .\gradlew.bat runClientGameTest "-Pgametest=VcsTagCommandGameTest,VcsCheckoutCommandGameTest,VcsDiffCommandGameTest,VcsPreviewCommandGameTest,VcsPlaceCommandGameTest"
    ```

## User-facing text

Applies to everything a player sees: the overlay, chat messages, action bar, hints, command help, key binds, settings and README.

- Write for a player who knows nothing about the mod and only wants their goal done. Say exactly what to do next ("Hit any block of your build", "Select a build first: hover it here and click Select").
- Keep it short. No technical details: no schematics, file paths, block updates, heads, build space or origins.
- Use these words only: **build**, **version**, **copy** (one appearance of a build in the world), **save**, **load**, **changes**, **label**, **select**, **hit** (a block). Never placement, commit, checkout, diff, tag, punch, uncommitted or modified blocks; say "unsaved changes". Code and `build.json` keep the internal names.
- Name keys and buttons the way the player sees them (Numpad 5, Left Alt, the button's own label), taking key names from the key mapping so rebinding shows the right key.
- Commands are `/vcs save`, `/vcs load`, `/vcs changes`, `/vcs label`, `/vcs unlabel`; arguments are `<copyname>` and `<label>`.
