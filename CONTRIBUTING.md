# Working on ScrCaster

`main` is the integration branch for Android, TV, and Linux. Use short-lived
branches for individual changes and merge them back after review and tests.
There is no separate `develop` branch. Release tags identify tested releases.

## Remotes and local defaults

`origin` is your fork (`3togo/ScrCaster`); `upstream` is
`https://github.com/Miuzarte/ScrcpyForAndroid.git`.
For a new clone, configure the upstream remote once:

```sh
git remote add upstream https://github.com/Miuzarte/ScrcpyForAndroid.git
git config remote.upstream.tagOpt --no-tags
git config remote.pushDefault origin
git config pull.ff only
git config merge.conflictStyle zdiff3
git config rerere.enabled true
```

These settings are local to each clone. Fetching upstream does not change your
working files. Never push ScrCaster changes to upstream directly.

## Start a change

Commit or stash unfinished work first, including any additional submodule edits.
Then:

```sh
git switch main
git pull --ff-only origin main
git switch -c codex/describe-the-change
```

Build with `./build.sh` (SDK setup is opt-in with `--setup-sdk`). Before merging,
run the checks for the platforms affected by the change. For a cross-platform
change, use all of these:

```sh
./build.sh assembleDebug testDebugUnitTest
bash gradlew -p desktop check installDist
xvfb-run -a bash gradlew -p desktop guiSmoke
```

Push the branch to `origin`, open a PR targeting `main`, and merge after the
checks pass. Delete the feature branch once merged. Keep `main` buildable.

## Sync upstream

Start from a clean parent worktree. Remove only the recorded Miuix patch before
updating its checkout; the helper refuses conflicting local edits.

```sh
git switch main
bash scripts/prepare-miuix.sh --reverse
git pull --ff-only origin main
git fetch --no-tags upstream
git switch -c codex/sync-upstream-YYYY-MM-DD
git merge --no-ff upstream/main
git submodule update --init --recursive
bash scripts/prepare-miuix.sh
```

If Git reports conflicts, resolve them and complete the merge before updating
submodules. If the Miuix patch no longer applies, refresh it for the selected
submodule revision instead of discarding it or overwriting local edits. Keep
the root AGP/Kotlin versions aligned with Miuix's version catalog.

Run the Android and Linux checks above, then submit the sync branch to `main`.
Use a **merge commit** for upstream sync PRs, never squash or rebase them: Git
must retain the upstream commits as ancestors to make the next sync incremental.

Android source packages and paths stay under
`io.github.miuzarte.scrcpyforandroid` to minimize upstream conflicts. The
installed app ID remains `io.github.togo3.scrcaster` (`.tvdebug` for debug), and
the display name stays ScrCaster. Desktop and shared core code live in their
own modules under `io.github.togo3.scrcaster`.

## Miuix compatibility patch

The submodule points to a published upstream commit. Our build fixes live in
`patches/miuix/build-compatibility.patch` (base: `91301e8936a2ec73b7b22f8ce643e2e0dfd593df`).
`build.sh` applies the patch idempotently; Android CI also builds through
`build.sh`. Before calling Gradle directly or opening Android Studio in a fresh
clone, run `bash scripts/prepare-miuix.sh` once.

An `m submodule/miuix` status after preparation is expected: it represents the
recorded patch. Do not commit an unpublished submodule commit or force-reset
the checkout to hide that status. To return to the pinned upstream files:

```sh
bash scripts/prepare-miuix.sh --reverse
```

## Releases and recovery

Create release tags from tested `main` commits with the prefix `scrcaster-v`
(for example `scrcaster-v0.6.0.1`). Only this prefix triggers the Android release
workflow. Push a specific release tag, not `--tags`: fetched upstream tags and
local archive tags are not ScrCaster releases.

The local tag `archive/linux-desktop-before-consolidation-2026-09-08` preserves
the original feature work and its uncommitted changes as a checkpoint. Its
commit remains in `main`'s history even after the old branches are deleted.
