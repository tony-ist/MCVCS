---
name: release
description: Bump the version (major, minor or patch), tag it, and publish a GitHub release with a changelog and the mod jar.
disable-model-invocation: true
argument-hint: "major|minor|patch"
allowed-tools: PowerShell(git *), PowerShell(gh *), PowerShell(.\gradlew.bat build*), PowerShell(Test-Path *), PowerShell(Get-ChildItem *), Read, Edit, Write
---

Bump: `$ARGUMENTS`. Must be `major`, `minor` or `patch`; otherwise stop and ask.

Use PowerShell only.

1. **Check**
   - On `main`, working tree clean (`git status --porcelain`), up to date with `origin/main` (`git fetch`, then `git status -sb` shows no `behind`). Otherwise stop.
   - `gh auth status` succeeds.
2. **Versions**
   - Current = `version` in `gradle.properties`. Previous tag = `git describe --tags --abbrev=0`. If they differ, stop and ask.
   - New = current with the requested part incremented and lower parts reset to 0 (`1.7.1` → major `2.0.0`, minor `1.8.0`, patch `1.7.2`).
   - Stop if tag `<new>` already exists locally or `gh release view <new>` succeeds.
   - Stop if `git log <prev>..HEAD` is empty - nothing to release.
3. **Changelog**: read `git log <prev>..HEAD --no-merges --format="%h %s%n%b"` and `git diff --stat <prev>..HEAD`. Write release notes to the scratchpad as `notes.md`:
   - Sections only when non-empty: `### Added`, `### Changed`, `### Fixed`.
   - User-facing wording, one line per change, merge related commits. Skip version bumps, README/test/refactor-only commits.
   - End with `**Full changelog**: https://github.com/tony-ist/MCVCS/compare/<prev>...<new>`.
   Show the new version and the notes to the user and wait for approval or edits before continuing.
4. **Bump and build**
   - Set `version=<new>` in `gradle.properties`.
   - `.\gradlew.bat build -x test`. Artifact = `build/libs/mcvcs-<new>-fabric+mc*.jar` excluding `-sources`; must be exactly one match.
   - If the build fails or the jar is missing, run `git checkout -- gradle.properties` and stop.
5. **Commit and tag**
   - `git commit -am "Bump version to <new>"`
   - `git tag <new>` (lightweight, like existing tags)
6. **Publish**
   - `git push origin main <new>`
   - `gh release create <new> <jar> --title "<new>" --notes-file <notes.md> --verify-tag`
7. Report the release URL.

Never move or delete existing tags. Never force-push, amend, or use `--no-verify`.
