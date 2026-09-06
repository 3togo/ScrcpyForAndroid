# Project memory

## Environment (host: Ubuntu 26.04, user eli)

- **scrcpy 4.1 is installed manually at `/usr/local/bin/scrcpy`** (self-contained:
  binary + `/usr/local/share/scrcpy/scrcpy-server`). apt/dpkg only knows the
  distro package `scrcpy 3.3.4-1` (`/usr/bin/scrcpy`). So `scrcpy --version`
  (4.1) and `apt-cache policy scrcpy` (3.3.4) disagree — this is expected.
- **`scrcpy-desktop` deb declares `Depends: scrcpy (>= 4.0)`** (in
  `desktop/package-deb.sh`). User REQUIRES this to stay at >= 4.0; do NOT lower it.
  Solution: `build.sh` now auto-installs the **real upstream scrcpy 4.1 deb**
  before the desktop deb install. `ensure_scrcpy()` in `build.sh`:
  - checks the apt/dpkg-visible version (`dpkg-query -W scrcpy`, epoch stripped);
    if major >= 4 it does nothing;
  - otherwise downloads
    `https://github.com/jakbin/scrcpy-deb/releases/download/4.1/scrcpy.deb`,
    falling back to `https://ghproxy.net/<same>` and `https://gh-proxy.com/<same>`
    (both GitHub and ghproxy returned 200 from this host);
  - installs it with `apt install -y` (sudo when not root).
  - Overrides: `SCRCPY_DEB_PATH=/path/to/scrcpy.deb` (offline; skips download) and
    `SCRCPY_DEB_VERSION` (default 4.1).
  - Called from `build_desktop()` right before `package-deb.sh --install`, so it
    covers `--desktop --install`, `--install deb`, and `--install both`.
  - A earlier hand-built dummy `scrcpy 4.1-0local` package was used briefly and has
    been REMOVED — prefer the real deb above.
  - **Verified working (2026-09-06)**: `./build.sh --desktop --install` installed
    `scrcpy 4.0` (the jakbin .deb's *package* version is `4.0` even though the
    release tag is 4.1 — still satisfies `>= 4.0`) and `scrcpy-desktop 0.5.5-1`.
  - Two benign messages appear at the end and are NOT failures:
    (a) `GDBus.Error:org.freedesktop.DBus.Error.TimedOut ... org.freedesktop.PackageKit`
        — GNOME PackageKit failed to activate to notice the change;
    (b) `Download is performed unsandboxed as root as file ... couldn't be accessed by
        user '_apt'` — the .deb lives under `/home/eli` (not traversable by `_apt`),
        so apt falls back to root. Harmless.
  - `install_package()` in `desktop/package-deb.sh` now copies the deb into a
    world-readable `mktemp -d` scratch dir and installs from there, then removes the
    scratch copy (original artifact at `$package_path` is untouched). This removes the
    recurring `_apt ... (13: Permission denied)` notice caused by `$HOME` being mode 750.
    Verified with stubbed `apt`/`sudo`: apt receives the /tmp path, scratch is cleaned
    up, artifact kept.
  - Resulting state: TWO scrcpy binaries — `/usr/local/bin/scrcpy` = 4.1 (manual,
    wins on PATH) and `/usr/bin/scrcpy` = 4.0 (from the jakbin deb). The desktop app
    resolves the binary via `System.getenv().getOrDefault("SCRCPY", "scrcpy")`
    (Backend.java:41), i.e. PATH, so it actually runs 4.1.
  - **jakbin deb contents**: its Debian `Version` field is `4.0`, but it actually ships
    scrcpy **4.1** — `/usr/bin/scrcpy --version` prints 4.1 — and it OWNS
    `/usr/local/share/scrcpy/scrcpy-server` (dpkg-registered). So the old manual
    `/usr/local/bin/scrcpy` 4.1 is now redundant.
  - `remove-scrcpy.sh` (repo root) removes scrcpy installs. Dry-run by default;
    `--local` (manual /usr/local files, uses sudo), `--apt` (apt purge), `--all`, `-y`.
    It skips any path owned by a dpkg package, and simulates `apt-get -s purge` to show
    cascading removals.
  - **Caution**: `apt purge scrcpy` also removes **scrcpy-desktop** (reverse dependency).
    To drop the duplicate manual install without breaking the desktop app, use
    `--local`, never `--apt`.
- `adb` is apt-installed (`1:34.0.5-12build1`); there is also a manual
  `/usr/local/bin/adb`.
- **sudo needs an interactive password here** (`sudo -n true` fails: "interactive
  authentication is required"), so any `sudo apt install ...` step must be run by
  the user in their own terminal — the assistant cannot perform it.

- **The assistant cannot view images here**: `read_file` on a PNG returns "the current
  model does not support images". NEVER claim a screenshot confirms something. To verify
  what is on a device screen, use `adb shell uiautomator dump /sdcard/x.xml`, `adb pull`,
  then `grep -oE 'text="[^"]+"' x.xml`. Gotcha: right after `am start`/`monkey` it fails
  with "ERROR: null root node returned by UiTestAutomationBridge" — wait ~10s and retry
  a few times. `am force-stop <pkg>` first if another screen (e.g. a session menu) is in
  front.

## Conventions

- Android devices attached are typically multiple (USB + wireless ADB);
  `build.sh --install apk` now prompts to choose a device. To drive it
  non-interactively, give it a pty:
  `printf '1\n' | script -qec 'bash ./build.sh --install apk' /dev/null`,
  or set `ANDROID_SERIAL`.
