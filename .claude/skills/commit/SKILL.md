---
name: commit
description: Commit all current changes in the working tree with a generated message. Does not push.
context: fork
model: sonnet
effort: medium
disable-model-invocation: true
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git add:*), Bash(git commit:*)
---

commit

Commit every currently changed file in this repository. Do not push to any remote.

Steps:
1. Run `git status` and `git diff` (plus `git diff --cached`) to see all modified, added, and deleted files, including untracked ones. If there is nothing to commit, say so and stop.
2. Run `git log --oneline -10` to match the repository's existing commit message style.
3. Stage everything with `git add -A`.
4. Write a commit message: a concise imperative summary line (under ~72 chars) describing the overall change, followed by a short body only if the change is non-trivial and needs context. Focus on *why* / *what*, not a file list.
5. Commit with `git commit`. Never use `--no-verify`, never amend, never push.
6. Report the resulting commit hash and summary line.

Do not modify any files other than through `git add`/`git commit`.
