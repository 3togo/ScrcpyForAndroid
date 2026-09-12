#!/usr/bin/env bash
# Install the Debian/Ubuntu packages this repository needs to build, run and
# test ScrCaster (APK build, desktop frontend, adb/scrcpy, window tooling).
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
    cat <<'EOF'
Usage: ./scripts/install-deps.sh [GROUP...] [options]

Installs the essential command line and desktop tools with apt, skipping
packages that are already present. Runs as root directly or through sudo.

Groups (default: base java adb scrcpy desktop python):
  base     ca-certificates, curl, git, unzip, zip      (needed by build.sh)
  java     a JDK; Gradle wants 21, the desktop build needs 17+
  adb      the adb client (USB and wireless debugging)
  scrcpy   the native scrcpy the desktop app drives
  desktop  wmctrl, xdotool, notify-send: drive and inspect scrcpy windows
  python   python3 for scripts/find-adb-tvs.py and scripts/test-sync-upstream.py
  build    build-essential, meson, ninja, pkg-config for compiling from source
  all      every group above

Options:
  --status          Report what is present or missing, install nothing.
  -n, --dry-run     Print the apt commands without running them.
  --no-update       Do not run 'apt-get update' first (faster with fresh lists).
  -h, --help        Show this help.

Examples:
  ./scripts/install-deps.sh                    # default groups
  ./scripts/install-deps.sh --status
  ./scripts/install-deps.sh base java build
  ./scripts/install-deps.sh all --dry-run

Notes:
  - Ubuntu keeps adb in the 'universe' component: enable it first with
    'sudo add-apt-repository universe' if a package is reported as unknown.
  - Most archives still ship scrcpy 3.x; the desktop .deb requires >= 4.0.
    './build.sh --desktop' downloads and installs a matching scrcpy .deb.
  - The Android SDK, NDK, platform and CMake are not apt packages here; use
    './build.sh --setup-sdk'.
  - For USB access outside a desktop login session, configure udev rules; see
    https://developer.android.com/studio/run/linux#udev
EOF
}

die() { printf 'install-deps.sh: %s\n' "$*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }

# is_installed / has_candidate need dpkg and apt-cache; both ship with apt. Both
# capture the output into a variable first: under 'set -o pipefail', a 'grep -q'
# that exits early makes the writer die of SIGPIPE and turns a match into a
# failed pipeline.
is_installed() {
    local status
    status="$(dpkg-query -W -f='${Status}' "$1" 2>/dev/null || true)"
    [[ "$status" == *'install ok installed'* ]]
}

# True when apt knows the name and has something to install. Unknown names are
# common across releases (openjdk-NN-jdk, android-tools-adb), so they are only
# reported, never fatal.
has_candidate() {
    local policy candidate
    policy="$(apt-cache policy -- "$1" 2>/dev/null || true)"
    candidate="$(sed -nE 's/^[[:space:]]*Candidate:[[:space:]]*(.*)$/\1/p' <<<"$policy")"
    [[ "$candidate" =~ ^[0-9] ]]
}

# A spec is one package name, or 'a|b' alternatives for the same role that got
# renamed across releases (pkg-config vs pkgconf). Echoes '<state> <name>', where
# state is 'installed', 'installable' or 'none'.
classify_spec() {
    local spec="$1" name='' chosen=''
    local -a names=()
    IFS='|' read -r -a names <<<"$spec"
    for name in "${names[@]}"; do
        if is_installed "$name"; then
            printf 'installed %s\n' "$name"
            return 0
        fi
        if [[ -z "$chosen" ]] && has_candidate "$name"; then
            chosen=$name
        fi
    done
    if [[ -n "$chosen" ]]; then
        printf 'installable %s\n' "$chosen"
    else
        printf 'none %s\n' "$spec"
    fi
}

dry_run=false
status_only=false
run_update=true
requested=()
while (($#)); do
    case "$1" in
        --status) status_only=true ;;
        -n|--dry-run) dry_run=true ;;
        --no-update) run_update=false ;;
        -h|--help) usage; exit 0 ;;
        --) die "Unexpected '--'; this script takes no trailing arguments." ;;
        -*) die "Unknown option: $1 (use --help)" ;;
        *) requested+=("$1") ;;
    esac
    shift
done

# Package lists per group. Every entry is checked against apt before use, so an
# renamed or archived package degrades to a warning instead of failing the run.
base_packages=(ca-certificates curl git unzip zip)
adb_packages=('adb|android-tools-adb')
scrcpy_packages=(scrcpy)
desktop_packages=(wmctrl xdotool libnotify-bin)
python_packages=(python3)
build_packages=(build-essential 'pkg-config|pkgconf' meson ninja-build)
# A JDK is chosen from the first name that apt can actually provide; a fresh
# archive may only carry 17, an old one may have neither.
java_candidates=(openjdk-21-jdk openjdk-17-jdk default-jdk)
java_resolved=()
resolve_java() {
    java_resolved=()
    for candidate in "${java_candidates[@]}"; do
        if is_installed "$candidate" || has_candidate "$candidate"; then
            java_resolved=("$candidate")
            return 0
        fi
    done
    java_resolved=("${java_candidates[0]}")
}
resolve_java

group_packages() {
    case "$1" in
        base) printf '%s\n' "${base_packages[@]}" ;;
        java) printf '%s\n' "${java_resolved[@]}" ;;
        adb) printf '%s\n' "${adb_packages[@]}" ;;
        scrcpy) printf '%s\n' "${scrcpy_packages[@]}" ;;
        desktop) printf '%s\n' "${desktop_packages[@]}" ;;
        python) printf '%s\n' "${python_packages[@]}" ;;
        build) printf '%s\n' "${build_packages[@]}" ;;
        *) return 1 ;;
    esac
}

all_groups=(base java adb scrcpy desktop python build)
if ((${#requested[@]})); then
    groups=()
    for want in "${requested[@]}"; do
        if [[ "$want" == all ]]; then
            groups=("${all_groups[@]}")
            break
        fi
        group_packages "$want" >/dev/null 2>&1 ||
            die "Unknown group: $want (see --help)"
        groups+=("$want")
    done
else
    groups=(base java adb scrcpy desktop python)
fi

if ! command -v apt-get >/dev/null 2>&1; then
    die "apt-get not found; this helper only supports Debian and Ubuntu family systems."
fi
os_id="$(. /etc/os-release 2>/dev/null && printf '%s %s' "${ID:-}" "${ID_LIKE:-}")"
case "$os_id" in
    *debian*|*ubuntu*) : ;;
    *) log "Warning: host reports '$os_id'; package names below may not match." ;;
esac
[[ -f "$project_dir/build.sh" ]] ||
    log "Warning: no build.sh in $project_dir; the next steps below may not apply."

if ((EUID == 0)); then
    apt_run() { "$@"; }
elif command -v sudo >/dev/null 2>&1; then
    apt_run() { sudo -- "$@"; }
else
    die "Not root and sudo is unavailable; run this script as root."
fi
apt_install() {
    local -a packages=("$@")
    ((${#packages[@]})) || return 0
    if "$dry_run"; then
        printf 'apt-get install -y -- %s\n' "${packages[*]}"
        return 0
    fi
    DEBIAN_FRONTEND=noninteractive apt_run apt-get install -y -- "${packages[@]}"
}

# apt must have updated lists before candidate lookups mean anything, so the
# resolution above is repeated afterwards when lists were just refreshed.
if "$run_update" && ! "$status_only"; then
    log "Refreshing apt lists"
    "$dry_run" || apt_run apt-get update
    # Candidate lookups only mean something once the lists are current.
    resolve_java
fi

missing=()
unknown=()
for group in "${groups[@]}"; do
    while IFS= read -r spec; do
        read -r state name < <(classify_spec "$spec")
        case "$state" in
            installed) : ;;
            installable) missing+=("$name") ;;
            *) unknown+=("$group/$spec") ;;
        esac
    done < <(group_packages "$group")
done

if "$status_only"; then
    log "Tool status"
    for group in "${groups[@]}"; do
        while IFS= read -r spec; do
            read -r state name < <(classify_spec "$spec")
            case "$state" in
                installed) printf '  [ok]      %-10s %s\n' "$group" "$name" ;;
                installable) printf '  [missing] %-10s %s\n' "$group" "$spec" ;;
                *) printf '  [unknown] %-10s %s\n' "$group" "$spec" ;;
            esac
        done < <(group_packages "$group")
    done
    printf '\n  java resolves to: %s\n' "${java_resolved[*]}"
    exit 0
fi

if ((${#unknown[@]})); then
    log "Not available from the configured apt sources: ${unknown[*]}"
    printf '    (on Ubuntu, enable universe: sudo add-apt-repository universe)\n'
fi

# De-duplicate while keeping order, in case a package sits in two groups.
install_list=()
seen=''
for package in "${missing[@]+"${missing[@]}"}"; do
    case "|$seen|" in *"|$package|"*) continue ;; esac
    seen+="|$package"
    install_list+=("$package")
done

if ((${#install_list[@]})); then
    log "Installing: ${install_list[*]}"
    apt_install "${install_list[@]}"
else
    log "Every requested package is already installed."
fi

# scrcpy >= 4.0 is what the desktop .deb depends on; distro packages lag behind.
if command -v scrcpy >/dev/null 2>&1; then
    scrcpy_version=''
    if [[ "$(scrcpy --version 2>/dev/null || true)" =~ ([0-9]+\.[0-9]+(\.[0-9]+)?) ]]; then
        scrcpy_version="${BASH_REMATCH[1]}"
    fi
    scrcpy_major="${scrcpy_version%%.*}"
    if [[ ! "$scrcpy_major" =~ ^[0-9]+$ ]] || ((scrcpy_major < 4)); then
        log "scrcpy ${scrcpy_version:-unknown} is older than 4.0; run './build.sh --desktop' to install a 4.x .deb."
    fi
fi

log "Versions"
for tool in java javac adb scrcpy wmctrl xdotool python3 git; do
    if command -v "$tool" >/dev/null 2>&1; then
        printf '  %-9s %s\n' "$tool" "$(command -v "$tool")"
    else
        printf '  %-9s %s\n' "$tool" "(not on PATH)"
    fi
done

cat <<EOF

Next steps:
  ./build.sh --setup-sdk      Prepare the Android SDK, NDK and CMake.
  ./build.sh                  Build the debug APKs.
  ./build.sh --install apk    Build and adb-install on the connected phone.
  ./build.sh --desktop        Build the Linux desktop frontend (installs scrcpy 4.x).
EOF
