# Project Memory

## Owner / repo
- GitHub repo: `3togo/ScrCaster`. History: `Miuzarte/ScrcpyForAndroid` → `3togo/ScrcpyForAndroid` → renamed to `3togo/ScrCaster` on 2026-09-08 (via `gh repo rename ScrCaster`).
- `gh` CLI is authenticated as the `3togo` account (token in OS keyring) with `repo`/`read:org`/`workflow`/`gist` scopes.
- Project / display name: **"ScrCaster"** — platform-neutral (Android app + Linux desktop), chosen to avoid confusion with Genymobile/scrcpy. Replaced "Scrcpy for Android" / `ScrcpyForAndroid`.
- Android source namespace: `io.github.miuzarte.scrcpyforandroid` (restored for easy upstream syncing). Installation applicationId remains `io.github.togo3.scrcaster`, with `.tvdebug` for debug; branding remains ScrCaster.
  - desktop app package: `io.github.togo3.scrcaster.desktop`; core library: `io.github.togo3.scrcaster.core` (group `io.github.togo3`).
  - JNI `FindClass` matches `io/github/miuzarte/scrcpyforandroid/nativecore/PairingContext`.
- Desktop module/binary renamed `scrcpy-desktop` → `scrcaster-desktop` (`desktop/settings.gradle.kts` rootProject.name).
- On-device storage directory renamed `Scrcpy` → `ScrCaster` (`PublicDirs.kt`, `FileManagerService.kt`, README FAQ).
- NOTE: lowercase `scrcpy` (the actual upstream tool) references in docs/code are intentional and must stay.

## Branching convention (decided 2026-09-08)
- Supersedes the earlier Git Flow decision: `main` is the single integration branch; use short-lived branches (normally `codex/*`) and merge back after checks. Retire `develop` and the long-lived Linux branch.
- `origin` is 3togo/ScrCaster; `upstream` is Miuzarte/ScrcpyForAndroid. Preserve upstream ancestry with merge commits; no squash/rebase for sync PRs. See CONTRIBUTING.md for the current workflow.

## Testing strategy (decided 2026-09-11)
- Prefer offline unit tests for deterministic behavior: connection state, validation, persistence, cancellation, and remote focus policy. They are faster, cheaper, repeatable, and do not depend on a TV, phone, ADB, network, or UI-window timing.
- Use instrumentation and real hardware only for platform or physical integration that cannot be established offline, then keep that to a focused smoke test when the feature is ready for release validation.
- If a UI focus test is flaky because of dialog or window timing, extract its navigation policy into a JVM test and leave a small UI test for reachability. Do not repeatedly consume hardware time to chase a timing assertion.

## miuix dependency (decided 2026-09-08)
- `submodule/miuix` (url `compose-miuix-ui/miuix.git`) is included as a composite build via `includeBuild("submodule/miuix")` in `settings.gradle.kts`.
- Pin a published upstream commit; `.gitmodules` `branch = main` only selects the branch for explicit remote updates. Build compatibility edits are tracked in `patches/miuix/build-compatibility.patch` and applied by `scripts/prepare-miuix.sh`. Reverse this patch before updating the submodule.
- Version catalog `gradle/libs.versions.toml` pins `miuix = "0.9.3"` but the composite build substitutes the project output, so the number is informational.
- **The main project's AGP + Kotlin versions MUST match the miuix submodule's** (`gradle/libs.versions.toml`: `agp = "9.4.0"`, `kotlin = "2.4.20"`). Keep version alignment when updating the submodule; store required compatibility changes as reviewed patches.
  - **Enforcement:** `build.sh` has a fail-fast `check_toolkit_versions` guard that compares `agp`/`kotlin` between `gradle/libs.versions.toml` and `submodule/miuix/gradle/libs.versions.toml` and aborts the build with a clear message on any drift (miuix is the source of truth). This prevents the confusing "multiple versions of the Android Gradle plugin" failure.
  - (Mismatch caused a build failure on 2026-09-08; fixed by aligning main to 9.4.0/2.4.20. Both files already match as of 2026-09-08.)
- After changing the miuix pin, verify the app still builds (miuix API drift risk).
