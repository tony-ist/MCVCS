- You are on Windows. Important: use only PowerShell, DO NOT use Bash. Bash cannot run .\gradlew.bat properly, it gets frozen and waits forever for it to finish.
- Use `gradlew.bat` to run gradle commands.
- Use `runClientGameTest` from `gradlew.bat` to run game tests and make screenshots, look at the resulting screenshots to verify your changes.
- To run a single game test: `gradlew.bat runClientGameTest -Pgametest=<SimpleClassName>` (e.g. `-Pgametest=VcsCheckoutCommandGameTest`; comma-separate several). Tests live in `src/gametest/java/tony/mcvcs/gametest/` and extend `VcsGameTest`.
- While developing a feature, run only the game test for that feature (it saves a lot of time not running all others). Before finishing your work, run all the related tests using `gradlew.bat runClientGameTest` with filter to make sure nothing else broke. Avoid running all tests to save time.
    Example:
    ```
    .\gradlew.bat runClientGameTest "-Pgametest=VcsTagCommandGameTest,VcsCheckoutCommandGameTest,VcsDiffCommandGameTest,VcsPreviewCommandGameTest,VcsLoadCommandGameTest,VcsPlaceCommandGameTest"
    ```
