#!/usr/bin/env bash
# Build a local Debian/Ubuntu package without root or an Android SDK.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/.." && pwd)
output_dir="$script_dir/build/distributions"
skip_build=false
offer_install=true
gradle_args=()

usage() {
    cat <<'HELP'
Usage: desktop/package-deb.sh [--skip-build] [--no-install] [--output-dir DIR] [-- GRADLE_OPTIONS...]

Builds check + installDist, then creates scrcpy-desktop_VERSION_all.deb.
Requires a JDK 17, dpkg-deb, dpkg, and standard Linux shell utilities.
After building, asks permission to install with apt in an interactive terminal.
apt installs missing runtime dependencies if available in configured repositories.

  --skip-build       Package an existing desktop/build/install/scrcpy-desktop.
  --no-install       Only build the package; never prompt or invoke apt/sudo.
  --output-dir DIR   Output directory (default: desktop/build/distributions).
  --help            Show this help.

Environment:
  GRADLE             Optional Gradle executable; otherwise use the repo wrapper.
  DEB_VERSION        Override package version (default: application version + -1).
  DEB_MAINTAINER     Package maintainer, e.g. 'Your Name <you@example.org>'.
                     Default: Local package builder <root@localhost>.

Example: desktop/package-deb.sh -- --offline --no-daemon
HELP
}
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
while (($#)); do
    case "$1" in
        --skip-build) skip_build=true; shift ;;
        --no-install) offer_install=false; shift ;;
        --output-dir) (($# >= 2)) || fail '--output-dir requires a directory'; output_dir=$2; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        --) shift; gradle_args=("$@"); break ;;
        *) fail "Unknown argument: $1 (use --help)" ;;
    esac
done
for tool in dpkg-deb dpkg install mktemp du awk find chmod gzip; do
    command -v "$tool" >/dev/null || fail "Missing build tool: $tool"
done

if ! "$skip_build"; then
    if [[ -n ${GRADLE:-} ]]; then
        "$GRADLE" -p "$script_dir" check installDist "${gradle_args[@]}"
    else
        bash "$repo_dir/gradlew" -p "$script_dir" check installDist "${gradle_args[@]}"
    fi
fi

dist_dir="$script_dir/build/install/scrcpy-desktop"
[[ -f "$dist_dir/bin/scrcpy-desktop" ]] || fail 'Distribution missing; run without --skip-build.'
shopt -s nullglob
app_jars=("$dist_dir"/lib/scrcpy-desktop-*.jar)
((${#app_jars[@]} == 1)) || fail 'Expected exactly one application jar in the distribution.'
app_version=${app_jars[0]##*/scrcpy-desktop-}
app_version=${app_version%.jar}
package_version=${DEB_VERSION:-$app_version-1}
# For a local binary package, reject epochs and unsafe filename/control characters.
[[ $package_version =~ ^[0-9][A-Za-z0-9.+~-]*$ ]] || fail 'Invalid DEB_VERSION.'
dpkg --validate-version "$package_version" || fail 'Invalid Debian package version.'
maintainer=${DEB_MAINTAINER:-Local package builder <root@localhost>}
[[ $maintainer != *$'\n'* && $maintainer != *$'\r'* && $maintainer == *'<'*'@'*'>' ]] || fail 'DEB_MAINTAINER must be one line: Name <email>.'

mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
staging=$(mktemp -d "$output_dir/.deb-stage.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT
package_root="$staging/root"
app_root="$package_root/usr/share/scrcpy-desktop"
doc_root="$package_root/usr/share/doc/scrcpy-desktop"
install -d -m 0755 "$package_root/DEBIAN" "$app_root/bin" "$app_root/lib" \
    "$package_root/usr/bin" "$package_root/usr/share/applications" \
    "$package_root/usr/share/icons/hicolor/scalable/apps" "$doc_root"
install -m 0755 "$dist_dir/bin/scrcpy-desktop" "$app_root/bin/"
install -m 0644 "$dist_dir"/lib/*.jar "$app_root/lib/"
ln -s ../share/scrcpy-desktop/bin/scrcpy-desktop "$package_root/usr/bin/scrcpy-desktop"
install -m 0644 "$repo_dir/app/src/main/assets/icon/icon.svg" \
    "$package_root/usr/share/icons/hicolor/scalable/apps/scrcpy-desktop.svg"
install -m 0644 "$repo_dir/LICENSE" "$doc_root/copyright"
gzip -n -c "$repo_dir/doc/LINUX.md" > "$doc_root/README.md.gz"
cat > "$package_root/usr/share/applications/scrcpy-desktop.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Scrcpy for Linux
Comment=Mirror and control Android devices over USB or Wi-Fi
Exec=scrcpy-desktop
TryExec=scrcpy-desktop
Icon=scrcpy-desktop
Terminal=false
Categories=Network;RemoteAccess;
Keywords=Android;ADB;Mirror;Wireless;
StartupNotify=false
DESKTOP
if command -v desktop-file-validate >/dev/null; then
    desktop-file-validate "$package_root/usr/share/applications/scrcpy-desktop.desktop"
fi
installed_size=$(du -sk "$package_root/usr" | awk '{print $1}')
cat > "$package_root/DEBIAN/control" <<CONTROL
Package: scrcpy-desktop
Version: $package_version
Section: utils
Priority: optional
Architecture: all
Maintainer: $maintainer
Installed-Size: $installed_size
Depends: openjdk-17-jre | java17-runtime, adb, scrcpy (>= 3.0)
Homepage: https://github.com/3togo/ScrcpyForAndroid
Description: Linux desktop frontend for Android mirroring
 Manage USB and wireless Android connections, pair using QR codes,
 and mirror devices with native scrcpy. Includes stream settings,
 recording, and file transfers. Requires a graphical desktop.
CONTROL
# Do not inherit the builder's restrictive umask into installed package files.
find "$package_root" -type d -exec chmod 0755 {} +
find "$package_root" -type f -exec chmod 0644 {} +
chmod 0755 "$app_root/bin/scrcpy-desktop"
package_path="$output_dir/scrcpy-desktop_${package_version}_all.deb"
dpkg-deb --root-owner-group --build "$package_root" "$staging/package.deb"
mv -f -- "$staging/package.deb" "$package_path"
printf '\nCreated: %s\nInstall: sudo apt install "%s"\n' "$package_path" "$package_path"

if "$offer_install" && [[ -t 0 && -t 1 ]]; then
    printf '\nInstall or update this package now? apt may install required runtime dependencies.\n'
    answer=''
    if read -r -p 'Allow apt installation (sudo may ask for your password)? [y/N] ' answer; then
        case "$answer" in
            y|Y|yes|YES|Yes)
                command -v apt >/dev/null || fail "apt is unavailable. Package saved at $package_path"
                if ((EUID == 0)); then
                    apt install -- "$package_path"
                else
                    command -v sudo >/dev/null || fail "sudo is unavailable. Package saved at $package_path"
                    sudo apt install -- "$package_path"
                fi
                ;;
            *) printf 'Installation skipped; the package is ready to install later.\n' ;;
        esac
    else
        printf '\nInstallation skipped; no confirmation received.\n'
    fi
elif "$offer_install"; then
    printf 'Non-interactive session: installation skipped. Run the install command above when ready.\n'
fi
