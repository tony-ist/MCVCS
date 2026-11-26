---
name: release
description: Bump the version (major, minor or patch) or publish the current one as is, tag it, and publish a GitHub release with a changelog and the mod jar.
disable-model-invocation: true
argument-hint: "major|minor|patch|publish"
allowed-tools: PowerShell(git *), PowerShell(gh *), PowerShell(.\gradlew.bat build*), PowerShell(Test-Path *), PowerShell(Get-ChildItem *), Read, Edit, Write
---

Mode: `$ARGUMENTS`. Must be `major`, `minor`, `patch` (bump) or `publish` (release the current version without bumping, e.g. the same version for another MC version). Otherwise stop and ask.

Use PowerShell only.

1. **Check**
   - Branch is `main` or `mc-*`, working tree clean (`git status --porcelain`), up to date with `origin/<branch>` (`git fetch`, then `git status -sb` shows no `behind`). Otherwise stop.
   - `gh auth status` succeeds.
2. **Versions**
   - MC = `minecraft_version` in `gradle.properties`. Current = `version` in `gradle.properties`.
   - Previous tag = `git describe --tags --abbrev=0`. Its version = the tag without any `+mc...` suffix.
   - Bump: if current differs from the previous tag's version, stop and ask. New = current with the requested part incremented and lower parts reset to 0 (`1.7.1` → major `2.0.0`, minor `1.8.0`, patch `1.7.2`).
   - Publish: new = current.
   - Tag = `<new>+mc<MC>`. Stop if it already exists locally or `gh release view <tag>` succeeds.
   - Stop if `git log <prev>..HEAD` is empty - nothing to release.
3. **Changelog**: write release notes to the scratchpad as `notes.md`.
   - Publish, and a release of `<new>` for another MC version exists (`gh release list`, tag `<new>` or `<new>+mc*`): reuse its notes (`gh release view <that tag> --json body`), add a line that this build is for MC `<MC>`, and fix the changelog link.
   - Otherwise read `git log <prev>..HEAD --no-merges --format="%h %s%n%b"` and `git diff --stat <prev>..HEAD`, then write:
     - Sections only when non-empty: `### Added`, `### Changed`, `### Fixed`.
     - User-facing wording, one line per change, merge related commits. Skip version bumps, README/test/refactor-only commits.
   - End with `**Full changelog**: https://github.com/tony-ist/MCVCS/compare/<prev>...<tag>` (write `+` as `%2B` in the URL).
   Show the tag and the notes to the user and wait for approval or edits before continuing.
4. **Build**
   - Bump only: set `version=<new>` in `gradle.properties`.
   - `.\gradlew.bat build -x test`. Artifact = `build/libs/mcvcs-<new>-fabric+mc<MC>.jar`; must exist.
   - If the build fails or the jar is missing, run `git checkout -- gradle.properties` and stop.
5. **Commit and tag**
   - Bump only: `git commit -am "Bump version to <new>"`
   - `git tag <tag>` (lightweight, like existing tags)
6. **Publish**
   - `git push origin <branch> <tag>`
   - `gh release create <tag> <jar> --title "<new> for MC <MC>" --notes-file <notes.md> --verify-tag`
   - Mark as latest (`--latest`) only from `main`; from `mc-*` pass `--latest=false`.
7. Report the release URL.

Never move or delete existing tags. Never force-push, amend, or use `--no-verify`.
