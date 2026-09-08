# Project Memory

## Owner / repo
- GitHub repo: `3togo/ScrCaster`. History: `Miuzarte/ScrcpyForAndroid` → `3togo/ScrcpyForAndroid` → renamed to `3togo/ScrCaster` on 2026-09-08 (via `gh repo rename ScrCaster`).
- `gh` CLI is authenticated as the `3togo` account (token in OS keyring) with `repo`/`read:org`/`workflow`/`gist` scopes.
- Project / display name: **"ScrCaster"** — platform-neutral (Android app + Linux desktop), chosen to avoid confusion with Genymobile/scrcpy. Replaced "Scrcpy for Android" / `ScrcpyForAndroid`.
- Package namespace / applicationId: `io.github.togo3.scrcaster` (MOVED from `io.github.miuzarte.scrcpyforandroid` on 2026-09-08 — a deliberate BREAKING change; existing installs are now a separate app and won't update in place).
  - desktop app package: `io.github.togo3.scrcaster.desktop`; core library: `io.github.togo3.scrcaster.core` (group `io.github.togo3`).
  - JNI `FindClass` in `app/src/main/jni/adb_pairing.cpp` updated to `io/github/togo3/scrcaster/nativecore/PairingContext`.
- Desktop module/binary renamed `scrcpy-desktop` → `scrcaster-desktop` (`desktop/settings.gradle.kts` rootProject.name).
- On-device storage directory renamed `Scrcpy` → `ScrCaster` (`PublicDirs.kt`, `FileManagerService.kt`, README FAQ).
- NOTE: lowercase `scrcpy` (the actual upstream tool) references in docs/code are intentional and must stay.

## Branching convention (decided 2026-09-08)
- Git Flow: `main` (stable) + `develop` (integration) + `feature/*`, `fix/*`.
- Dropped the `codex/` AI-agent prefix. `codex/linux-desktop-support` renamed to `feature/linux-desktop-support`.

## miuix dependency (decided 2026-09-08)
- `submodule/miuix` (url `compose-miuix-ui/miuix.git`) is included as a composite build via `includeBuild("submodule/miuix")` in `settings.gradle.kts`.
- Configured to **track upstream `main`** (`.gitmodules` has `branch = main`); keep it identical to miuix.git, no local fork/divergence.
- Version catalog `gradle/libs.versions.toml` pins `miuix = "0.9.3"` but the composite build substitutes the project output, so the number is informational.
- **The main project's AGP + Kotlin versions MUST match the miuix submodule's** (`gradle/libs.versions.toml`: `agp = "9.4.0"`, `kotlin = "2.4.20"`). Gradle forbids two AGP versions in one build, and the submodule must NOT be modified — so when miuix bumps its versions, bump the main `gradle/libs.versions.toml` to match.
  - **Enforcement:** `build.sh` has a fail-fast `check_toolkit_versions` guard that compares `agp`/`kotlin` between `gradle/libs.versions.toml` and `submodule/miuix/gradle/libs.versions.toml` and aborts the build with a clear message on any drift (miuix is the source of truth). This prevents the confusing "multiple versions of the Android Gradle plugin" failure.
  - (Mismatch caused a build failure on 2026-09-08; fixed by aligning main to 9.4.0/2.4.20. Both files already match as of 2026-09-08.)
- After changing the miuix pin, verify the app still builds (miuix API drift risk).
