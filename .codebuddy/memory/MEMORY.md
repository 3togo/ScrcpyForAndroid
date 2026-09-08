# Project Memory

## Owner / repo
- GitHub repo: `3togo/ScrCaster` (formerly `3togo/ScrCaster`). Keep `Miuzarte`→`3togo` in docs/refs.
- applicationId / namespace: `io.github.3togo.scrcaster` — intentionally kept (non-breaking for existing installs). Do NOT change without explicit request.
- Display/project name: **"ScrCaster"** (platform-neutral; renamed from "Scrcpy for Android" / `ScrCaster` on 2026-09-08 because the project also ships a Linux desktop frontend and must NOT be confused with Genymobile/scrcpy).
  - `settings.gradle.kts` rootProject.name = `ScrCaster`
  - `app/src/main/res/values*/strings.xml` `app_name` = `ScrCaster`
  - `README.md` title = `# ScrCaster`
  - `AboutScreen.kt` displayed name = `ScrCaster`
  - workspace file renamed `ScrCaster.code-workspace` → `ScrCaster.code-workspace`
  - NOTE: lowercase `scrcpy` (the actual tool) references in docs/code are intentional and must stay.
- GitHub repo is still `3togo/ScrCaster` (manual rename pending); README/deb Homepage URLs still reference it.

## Branching convention (decided 2026-09-08)
- Git Flow: `main` (stable) + `develop` (integration) + `feature/*`, `fix/*`.
- Dropped the `codex/` AI-agent prefix. `codex/linux-desktop-support` renamed to `feature/linux-desktop-support`.

## miuix dependency (decided 2026-09-08)
- `submodule/miuix` (url `compose-miuix-ui/miuix.git`) is included as a composite build via `includeBuild("submodule/miuix")` in `settings.gradle.kts`.
- Configured to **track upstream `main`** (`.gitmodules` has `branch = main`); keep it identical to miuix.git, no local fork/divergence.
- Version catalog `gradle/libs.versions.toml` pins `miuix = "0.9.3"` but the composite build substitutes the project output, so the number is informational.
- After changing the miuix pin, verify the app still builds (miuix API drift risk).
