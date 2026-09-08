#!/usr/bin/env bash
# Remove an existing scrcpy installation from this machine.
#
# Two independent installations can coexist:
#   local - installed from the upstream tarball into /usr/local (NOT dpkg-managed)
#   apt   - installed as a Debian package (e.g. the jakbin scrcpy 4.0 .deb that
#           build.sh installs to satisfy scrcaster-desktop's "scrcpy (>= 4.0)")
#
# SAFE BY DEFAULT: with no mode it only reports what it found / would remove.
# Pass --local, --apt or --all (plus -y, or confirm interactively) to actually delete.
set -euo pipefail

prog="${0##*/}"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '\033[1;33mWarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<EOF
Usage: $prog [MODE] [options]

Modes (what to remove):
  --local   Remove the manually installed scrcpy under /usr/local (upstream tarball)
  --apt     Remove the Debian package 'scrcpy' via apt (dpkg)
  --all     Remove both
  (none)    Dry run: report only, change nothing

Options:
  -y, --yes   Do not ask for confirmation
  -h, --help  Show this help

Examples:
  $prog                 # inspect what is installed
  $prog --local         # delete /usr/local/bin/scrcpy and its data files
  $prog --apt           # purge the scrcpy package (may also remove dependents!)
  $prog --all -y        # remove both, no prompt
EOF
}

mode=""
assume_yes=false
while (($#)); do
    case "$1" in
        --local) mode=local ;;
        --apt) mode=apt ;;
        --all) mode=all ;;
        -y|--yes) assume_yes=true ;;
        -h|--help) usage; exit 0 ;;
        *) die "Unknown option: $1 (try --help)" ;;
    esac
    shift
done

# ---------------------------------------------------------------- detection ---
apt_version=""
dpkg-query -W -f='${Version}' scrcpy >/dev/null 2>&1 &&
    apt_version="$(dpkg-query -W -f='${Version}' scrcpy 2>/dev/null)"

# Files belonging to a manual (non-dpkg) install.
local_paths=(
    /usr/local/bin/scrcpy
    /usr/local/share/scrcpy
    /usr/local/share/man/man1/scrcpy.1
    /usr/local/share/man/man1/scrcpy.1.gz
    /usr/local/share/applications/scrcpy.desktop
    /usr/local/share/applications/com.genymobile.scrcpy.desktop
    /usr/local/share/icons/hicolor/256x256/apps/scrcpy.png
    /usr/local/share/icons/hicolor/scalable/apps/scrcpy.svg
    /usr/local/share/bash-completion/completions/scrcpy
    /usr/local/share/zsh/site-functions/_scrcpy
    /opt/scrcpy
)
# Catch anything else the upstream install dropped under /usr/local or /opt.
while IFS= read -r found; do
    local_paths+=("$found")
done < <(find /usr/local /opt -maxdepth 6 -iname '*scrcpy*' 2>/dev/null || true)

# Existing + de-duplicated, preserving order.
existing=()
for p in "${local_paths[@]}"; do
    [[ -e "$p" || -L "$p" ]] || continue
    # Never touch anything owned by a dpkg package (that is the apt install's job).
    if dpkg -S "$p" >/dev/null 2>&1; then
        continue
    fi
    already=false
    for q in "${existing[@]+"${existing[@]}"}"; do
        [[ "$q" == "$p" ]] && already=true && break
    done
    "$already" || existing+=("$p")
done

log "Detection"
if [[ -n "$apt_version" ]]; then
    info "apt package : scrcpy $apt_version"
else
    info "apt package : not installed"
fi
if ((${#existing[@]})); then
    info "local files : ${#existing[@]} path(s)"
    for p in "${existing[@]}"; do info "  - $p"; done
else
    info "local files : none"
fi
running="$(command -v scrcpy || true)"
info "on PATH     : ${running:-none}$([[ -n "$running" ]] && printf ' (%s)' "$(scrcpy --version 2>/dev/null | head -1 || echo unknown)")"

# What would apt take with it? (scrcaster-desktop depends on scrcpy, so purging scrcpy
# can remove the desktop app too - always show this before doing anything.)
apt_removals=()
if [[ -n "$apt_version" ]] && command -v apt-get >/dev/null 2>&1; then
    while IFS= read -r line; do
        apt_removals+=("${line}")
    done < <(apt-get -s purge scrcpy 2>/dev/null |
        sed -nE 's/^(Remv|Purg) ([^ ]+).*/\2/p' | sort -u || true)
fi
if ((${#apt_removals[@]})); then
    log "apt would also remove:"
    for p in "${apt_removals[@]}"; do info "  - $p"; done
    for p in "${apt_removals[@]}"; do
        if [[ "$p" == scrcaster-desktop ]]; then
            warn "purging scrcpy also removes scrcaster-desktop (it depends on scrcpy)."
        fi
    done
fi

# ------------------------------------------------------------------ dry run ---
if [[ -z "$mode" ]]; then
    log "Dry run - nothing was changed. Re-run with --local, --apt or --all to remove."
    exit 0
fi

want_local=false want_apt=false
case "$mode" in
    local) want_local=true ;;
    apt) want_apt=true ;;
    all) want_local=true; want_apt=true ;;
esac

if "$want_local" && ((${#existing[@]} == 0)); then
    log "No local scrcpy files to remove."
fi
if "$want_apt" && [[ -z "$apt_version" ]]; then
    log "No apt scrcpy package to remove."
fi

if ! "$assume_yes"; then
    printf '\nAbout to remove: %s\n' "$mode"
    "$want_local" && ((${#existing[@]})) && printf '  local files: %s\n' "${#existing[@]}"
    "$want_apt" && [[ -n "$apt_version" ]] && printf '  apt package: scrcpy %s\n' "$apt_version"
    answer=''
    read -r -p 'Proceed? [y/N] ' answer || answer=''
    case "$answer" in
        y|Y|yes|YES|Yes) ;;
        *) printf 'Aborted; nothing was changed.\n'; exit 0 ;;
    esac
fi

# ------------------------------------------------------------------ removal ---
status=0

if "$want_local" && ((${#existing[@]})); then
    log "Removing local scrcpy files"
    # Files under /usr/local are root-owned, so elevation is normally required.
    if ((EUID != 0)); then
        command -v sudo >/dev/null 2>&1 ||
            die "sudo is unavailable; run this script as root to remove files under /usr/local."
    fi
    rm_path() {
        if ((EUID == 0)); then
            rm -rf -- "$1"
        else
            sudo rm -rf -- "$1"
        fi
    }
    for p in "${existing[@]}"; do
        if rm_path "$p"; then
            info "removed $p"
        else
            warn "could not remove $p"
            status=1
        fi
    done
fi

if "$want_apt" && [[ -n "$apt_version" ]]; then
    log "Purging apt package scrcpy"
    if ((EUID == 0)); then
        apt-get purge -y scrcpy || status=1
    else
        command -v sudo >/dev/null 2>&1 ||
            die "sudo is unavailable; run this script as root."
        sudo apt-get purge -y scrcpy || status=1
    fi
fi

printf '\n'
log "Result"
info "on PATH now : $(command -v scrcpy || echo 'none')"
if [[ -n "$(command -v scrcpy || true)" ]]; then
    info "version     : $(scrcpy --version 2>/dev/null | head -1 || echo unknown)"
fi
dpkg-query -W -f='${Version}' scrcpy >/dev/null 2>&1 &&
    info "apt package : scrcpy $(dpkg-query -W -f='${Version}' scrcpy 2>/dev/null)" ||
    info "apt package : not installed"

exit "$status"
