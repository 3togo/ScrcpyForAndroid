#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

usage() {
    cat <<'EOF'
Usage: ./build.sh [--setup-sdk [--accept-licenses]] [Gradle arguments...]
       ./build.sh --desktop [Gradle arguments...]
       ./build.sh --install [apk|deb|both] [Gradle arguments...]

By default, use the existing Android SDK and build ARM64 (arm64-v8a),
ARMv7 (armeabi-v7a), and universal debug APKs (assembleDebug).
Override architectures with -PabiList=... (comma-separated).
With --setup-sdk, install missing SDK command-line tools, platform, build-tools,
NDK and CMake, and display SDK licenses for acceptance before building.

  --setup-sdk       Prepare the Android SDK before building.
  --accept-licenses  Answer yes to Android SDK license prompts (for CI/SSH).
                    Use with --setup-sdk only if you agree to the license terms.
  --skip-sdk-setup   Use an already prepared SDK (default).
  --desktop         Build the Linux desktop app without Android SDK setup.
                    Default tasks: check installDist.
  --install [WHAT]  Build, then install what was built:
                      1) apk  - assembleDebug, then adb install -r
                      2) deb  - desktop package, then sudo apt install
                      3) both - 1) followed by 2)
                    Without WHAT an interactive menu asks which one to run;
                    a non-interactive shell requires WHAT.
                    Gradle arguments, when given, are used for every build run;
                    with a target they must follow '--' or the target name.
  -h, --help        Show this help.
  --                Pass all remaining arguments directly to Gradle.

Examples:
  ./build.sh
  ./build.sh --setup-sdk
  ./build.sh --setup-sdk --accept-licenses
  ./build.sh clean assembleDebug -PabiList=arm64-v8a
  ./build.sh assembleDebug -PabiList=arm64-v8a,armeabi-v7a,x86,x86_64
  ./build.sh assembleDebug --offline
  ./build.sh --desktop
  ./build.sh --desktop --install
  ./build.sh --install          # menu: 1) apk  2) deb  3) both
  ./build.sh --install apk
  ./build.sh --install deb
  ./build.sh --install both

SDK location: local.properties sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT,
then an existing SDK in ~/Android/Sdk, ~/Android or ~/.android.
A fresh SDK defaults to ~/Android/Sdk. SDKMANAGER can select a specific tool.
APK installation uses adb from the SDK, from $ADB, or from PATH. With more
than one device attached, choose device numbers (e.g. 1,2) or all; in a
non-interactive shell set ANDROID_SERIAL to select the target device.
Requires Bash, Java 17+ (JDK 21 recommended), and Git for missing submodules.
Automatic command-line tools download requires Linux x86_64, curl, unzip and
sha256sum. Other hosts must provide sdkmanager themselves.
EOF
}

die() { printf 'build.sh: %s\n' "$*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "Required command missing: $1"; }

accept_licenses=false
skip_sdk_setup=true
desktop=false
install_requested=false
install_target=""
gradle_args=()
user_gradle_args=false
while (($#)); do
    case "$1" in
        --accept-licenses) accept_licenses=true ;;
        --setup-sdk) skip_sdk_setup=false ;;
        --skip-sdk-setup) skip_sdk_setup=true ;;
        --desktop) desktop=true ;;
        --install)
            install_requested=true
            # Take the next non-option argument as the target; Gradle arguments
            # must come after '--' or after the target.
            if (($# >= 2)) && [[ $2 != -* ]]; then
                install_target=$2
                shift
            fi
            ;;
        --install=*)
            install_requested=true
            install_target=${1#--install=}
            ;;
        -h|--help) usage; exit 0 ;;
        --) shift; gradle_args+=("$@"); user_gradle_args=true; break ;;
        *) gradle_args+=("$1"); user_gradle_args=true ;;
    esac
    shift
done

case "$(printf '%s' "$install_target" | tr '[:upper:]' '[:lower:]')" in
    "") : ;; # No --install target given; menu or --desktop decides later.
    apk|android) install_target=apk ;;
    deb|desktop) install_target=deb ;;
    both|all) install_target=both ;;
    *) die "Invalid --install target: $install_target (use apk, deb or both)." ;;
esac
# --desktop --install keeps meaning "install the .deb".
if "$install_requested" && [[ -z "$install_target" ]] && "$desktop"; then
    install_target=deb
fi

# Sets install_target from an interactive menu (stdout must stay a terminal).
choose_install_target() {
    printf '\nWhat do you want to build and install?\n'
    printf '  1) Android APK   assembleDebug, then adb install -r\n'
    printf '  2) Linux .deb    desktop app, then sudo apt install\n'
    printf '  3) Both          1) followed by 2)\n'
    local answer=''
    if ! read -r -p 'Choose 1, 2 or 3 [1]: ' answer; then
        die "No choice received; expected 1, 2 or 3."
    fi
    case "$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')" in
        1|apk|android|'') install_target=apk ;;
        2|deb|desktop) install_target=deb ;;
        3|both|all) install_target=both ;;
        *) die "Unknown choice: $answer (expected 1, 2 or 3)." ;;
    esac
}

if "$install_requested" && [[ -z "$install_target" ]]; then
    [[ -t 0 && -t 1 ]] || die "--install needs a target in a non-interactive shell: --install apk, --install deb or --install both."
    choose_install_target
fi

target_apk=false
target_deb=false
case "$install_target" in
    apk) target_apk=true ;;
    deb) target_deb=true ;;
    both) target_apk=true; target_deb=true ;;
esac
if "$desktop"; then
    if "$target_apk"; then
        die "--desktop cannot be combined with --install apk."
    fi
    target_deb=true
fi

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$java_bin" >/dev/null 2>&1 || die "Install JDK 21 or set JAVA_HOME to an installed JDK."
java_version_output="$("$java_bin" -version 2>&1)"
java_major="$(sed -nE 's/.*version "([0-9]+).*/\1/p' <<<"$java_version_output")"
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || ((java_major < 17)); then
    die "Java 17+ is required; install JDK 21 or fix JAVA_HOME."
fi

# scrcaster-desktop Depends: scrcpy (>= 4.0) (see desktop/package-deb.sh), but most
# distros still ship 3.x, and a manually installed /usr/local/bin/scrcpy is invisible
# to apt. Install the upstream 4.1 .deb so apt can resolve the dependency.
scrcpy_min_major=4
scrcpy_deb_version="${SCRCPY_DEB_VERSION:-4.1}"
# GitHub first; ghproxy.net / gh-proxy.com are fallbacks for restricted networks.
scrcpy_deb_urls=(
    "https://github.com/jakbin/scrcpy-deb/releases/download/${scrcpy_deb_version}/scrcpy.deb"
    "https://ghproxy.net/https://github.com/jakbin/scrcpy-deb/releases/download/${scrcpy_deb_version}/scrcpy.deb"
    "https://gh-proxy.com/https://github.com/jakbin/scrcpy-deb/releases/download/${scrcpy_deb_version}/scrcpy.deb"
)

# scrcpy version as apt/dpkg sees it; empty when not installed as a package.
apt_scrcpy_version() {
    dpkg-query -W -f='${Version}' scrcpy 2>/dev/null || true
}

scrcpy_satisfied() {
    local version major
    version="$(apt_scrcpy_version)"
    [[ -n "$version" ]] || return 1
    version="${version#*:}" # strip epoch, e.g. 1:3.3.4-1 -> 3.3.4-1
    major="${version%%.*}"
    [[ "$major" =~ ^[0-9]+$ ]] || return 1
    ((major >= scrcpy_min_major))
}

download_scrcpy_deb() {
    local dest="$1" url
    for url in "${scrcpy_deb_urls[@]}"; do
        log "Trying $url"
        if command -v curl >/dev/null 2>&1 &&
            curl -fL --retry 2 --connect-timeout 15 -o "$dest" "$url"; then
            return 0
        fi
        if command -v wget >/dev/null 2>&1 &&
            wget -q --tries=2 --timeout=20 -O "$dest" "$url"; then
            return 0
        fi
    done
    return 1
}

apt_install_local_deb() {
    local deb="$1"
    command -v apt >/dev/null 2>&1 || return 1
    if ((EUID == 0)); then
        apt install -y -- "$deb"
    elif command -v sudo >/dev/null 2>&1; then
        sudo apt install -y -- "$deb"
    else
        return 1
    fi
}

ensure_scrcpy() {
    if scrcpy_satisfied; then
        log "scrcpy $(apt_scrcpy_version) is already registered with apt."
        return 0
    fi
    log "apt has no scrcpy >= ${scrcpy_min_major}.0; installing scrcpy ${scrcpy_deb_version}."
    # Offline escape hatch: point SCRCPY_DEB_PATH at a locally downloaded scrcpy .deb.
    if [[ -n "${SCRCPY_DEB_PATH:-}" ]]; then
        [[ -f "$SCRCPY_DEB_PATH" ]] || die "SCRCPY_DEB_PATH is not a file: $SCRCPY_DEB_PATH"
        log "Installing local scrcpy package: $SCRCPY_DEB_PATH"
        apt_install_local_deb "$SCRCPY_DEB_PATH" ||
            die "Failed to install $SCRCPY_DEB_PATH. Install scrcpy >= ${scrcpy_min_major}.0 manually."
        return 0
    fi
    local tmp dest
    tmp="$(mktemp -d)" || die "Could not create a temporary directory."
    dest="$tmp/scrcpy.deb"
    if ! download_scrcpy_deb "$dest"; then
        rm -rf -- "$tmp"
        die "Could not download the scrcpy ${scrcpy_deb_version} .deb. Set SCRCPY_DEB_PATH to a local copy, or install scrcpy >= ${scrcpy_min_major}.0 yourself."
    fi
    if ! apt_install_local_deb "$dest"; then
        rm -rf -- "$tmp"
        die "Failed to install the downloaded scrcpy package. Install scrcpy >= ${scrcpy_min_major}.0 manually, then re-run."
    fi
    rm -rf -- "$tmp"
    scrcpy_satisfied ||
        log "Warning: apt still does not see scrcpy >= ${scrcpy_min_major}.0; the .deb install may fail."
}

# Build the Linux desktop app; with --install deb it also packages and installs it.
build_desktop() {
    local -a tasks=("$@")
    ((${#tasks[@]})) || tasks=(check installDist)
    log "Running Gradle (desktop): ${tasks[*]}"
    if "$target_deb" && "$install_requested"; then
        ensure_scrcpy
        # package-deb.sh runs the Gradle build, packages the .deb, then apt installs it.
        bash "$project_dir/desktop/package-deb.sh" --install -- "${tasks[@]}"
    else
        bash "$project_dir/gradlew" -p "$project_dir/desktop" "${tasks[@]}"
    fi
}

# Install the freshly built debug APK on the selected connected devices.
install_apk() {
    local adb="${ADB:-}"
    if [[ -z "$adb" ]]; then
        if [[ -x "$sdk_dir/platform-tools/adb" ]]; then
            adb="$sdk_dir/platform-tools/adb"
        else
            adb="$(command -v adb || true)"
        fi
    fi
    [[ -n "$adb" ]] || die "adb not found; install Android platform-tools or set ADB=/path/to/adb."
    local -a adb_cmd=("$adb")
    [[ -z "${ANDROID_SERIAL:-}" ]] || adb_cmd+=(-s "$ANDROID_SERIAL")

    local devices device_count=0 serial index=1 answer='' choice
    local -a available_devices=() selected_devices=() choices=() selected_indices=()
    devices="$("${adb_cmd[@]}" devices | awk 'NR > 1 && $2 == "device" {print $1}')"
    [[ -n "$devices" ]] || die "No Android device connected. Connect one, start adb, or set ANDROID_SERIAL."
    while IFS= read -r serial; do
        [[ -n "$serial" ]] && available_devices+=("$serial")
    done <<<"$devices"
    device_count=${#available_devices[@]}
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        printf "%s" "$devices" | grep -qx -- "$ANDROID_SERIAL" \
            || die "ANDROID_SERIAL=$ANDROID_SERIAL is not connected."
        selected_devices=("$ANDROID_SERIAL")
    else
        selected_devices=("${available_devices[0]}")
    fi
    if ((device_count > 1)) && [[ -z "${ANDROID_SERIAL:-}" ]]; then
        if [[ ! -t 0 || ! -t 1 ]]; then
            die "More than one device connected; set ANDROID_SERIAL first: ${devices//$'\n'/ }"
        fi
        printf '\nMore than one device is connected. Choose which to install on:\n'
        while IFS= read -r serial; do
            [[ -n "$serial" ]] || continue
            printf '  %d) %s\n' "$index" "$serial"
            index=$((index + 1))
        done <<<"$devices"
        printf '  all) All connected devices\n'
        if ! read -r -p "Choose devices (e.g. 1,2 or all) [1]: " answer; then
            die "No device selected; expected device numbers or all."
        fi
        answer="$(printf '%s' "$answer" | tr -d '[:space:]')"
        [[ -z "$answer" ]] && answer=1
        if [[ "${answer,,}" == all ]]; then
            selected_devices=("${available_devices[@]}")
        else
            [[ "$answer" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] \
                || die "Invalid choice: $answer (expected device numbers separated by commas, or all)."
            IFS=, read -r -a choices <<<"$answer"
            selected_devices=()
            for choice in "${choices[@]}"; do
                # Compare against valid indices before using input in arithmetic.
                for ((index = 1; index <= device_count; index++)); do
                    [[ "$choice" == "$index" ]] && break
                done
                ((index <= device_count)) || die "Invalid device number: $choice (expected 1 to $device_count)."
                if [[ -z "${selected_indices[index]:-}" ]]; then
                    selected_devices+=("${available_devices[index - 1]}")
                    selected_indices[index]=1
                fi
            done
        fi
    fi

    for serial in "${selected_devices[@]}"; do
        log "Using device: $serial"
        install_apk_on_device "$adb" "$serial"
    done
}

install_apk_on_device() {
    local -a adb_cmd=("$1" -s "$2")
    local apk_dir="$project_dir/app/build/outputs/apk/debug"
    local previous_nullglob
    previous_nullglob="$(shopt -p nullglob || true)"
    shopt -s nullglob
    local -a apks=("$apk_dir"/*.apk)
    eval "$previous_nullglob"
    ((${#apks[@]})) || die "No APK found in $apk_dir."

    local apk=""
    if ((${#apks[@]} == 1)); then
        apk=${apks[0]}
    else
        # Split builds: prefer the device ABI, then the universal APK.
        local device_abi
        device_abi="$("${adb_cmd[@]}" shell getprop ro.product.cpu.abi | tr -d '\r\n')"
        local candidate
        for candidate in "${apks[@]}"; do
            if [[ ${candidate##*/} == *"$device_abi"* ]]; then
                apk=$candidate
                break
            fi
        done
        if [[ -z "$apk" ]]; then
            for candidate in "${apks[@]}"; do
                if [[ ${candidate##*/} == *universal* ]]; then
                    apk=$candidate
                    break
                fi
            done
        fi
        [[ -n "$apk" ]] || die "Cannot pick an APK for ${device_abi:-this device} in ${apks[*]}."
    fi

    log "Installing APK: $apk"
    local install_output
    if install_output="$("${adb_cmd[@]}" install -r "$apk" 2>&1)"; then
        echo "$install_output"
        return 0
    fi
    echo "$install_output"
    if [[ "$install_output" == *INSTALL_FAILED_NO_MATCHING_ABIS* ]]; then
        local universal
        for universal in "${apks[@]}"; do
            [[ ${universal##*/} == *universal* ]] || continue
            log "No native libraries for this device in $apk; retrying with the universal APK."
            "${adb_cmd[@]}" install -r "$universal" && return 0
            break
        done
    fi
    if [[ "$install_output" == *"User rejected permissions"* ]]; then
        die "The device rejected the install: accept the install prompt on the device screen (or install over USB), then retry."
    fi
    if [[ "$install_output" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
        die "The installed app was signed with a different key: uninstall it first, then retry."
    fi
    die "adb install failed for $apk."
}

# Deb-only runs need no Android SDK at all.
if "$target_deb" && ! "$target_apk"; then
    if "$user_gradle_args"; then
        build_desktop "${gradle_args[@]}"
    else
        build_desktop
    fi
    exit 0
fi

# Match Gradle's precedence so licenses and packages go into the SDK it uses.
sdk_dir=""
if [[ -f local.properties ]]; then
    sdk_dir="$(sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*(.*)$/\1/p' local.properties | tail -n 1)"
    sdk_dir="${sdk_dir%$'\r'}"
    sdk_dir="${sdk_dir//\\:/:}"
    sdk_dir="${sdk_dir//\\ / }"
    sdk_dir="${sdk_dir//\\\\/\\}"
fi
sdk_dir="${sdk_dir:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [[ -z "$sdk_dir" ]]; then
    for candidate in "$HOME/Android/Sdk" "$HOME/Android" "$HOME/.android"; do
        if [[ -d "$candidate/platforms" || -d "$candidate/cmdline-tools" ]]; then
            sdk_dir="$candidate"
            break
        fi
    done
fi
sdk_dir="${sdk_dir:-$HOME/Android/Sdk}"
[[ "$sdk_dir" = /* ]] || sdk_dir="$project_dir/$sdk_dir"
export ANDROID_HOME="$sdk_dir" ANDROID_SDK_ROOT="$sdk_dir"
log "Android SDK: $sdk_dir"

temp_dir=""
cleanup() { if [[ -n "$temp_dir" ]]; then rm -rf -- "$temp_dir"; fi; }
trap cleanup EXIT

if ! "$skip_sdk_setup"; then
    mkdir -p "$sdk_dir"
    [[ -w "$sdk_dir" ]] || die "SDK directory is not writable: $sdk_dir"

    sdkmanager="${SDKMANAGER:-}"
    if [[ -n "$sdkmanager" ]]; then
        sdkmanager="$(command -v "$sdkmanager")" || die "SDKMANAGER does not name an executable."
    else
        for candidate in "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" \
            "$sdk_dir"/cmdline-tools/*/bin/sdkmanager "$sdk_dir/tools/bin/sdkmanager"; do
            if [[ -x "$candidate" ]]; then
                sdkmanager="$candidate"
                break
            fi
        done
        sdkmanager="${sdkmanager:-$(command -v sdkmanager || true)}"
    fi

    temp_dir="$(mktemp -d)"
    if [[ -z "$sdkmanager" ]]; then
        [[ "$(uname -s)" = Linux && "$(uname -m)" = x86_64 ]] || die "Install Android SDK command-line tools and set SDKMANAGER to their sdkmanager executable."
        for command in curl unzip sha256sum; do require_command "$command"; done
        # Pinned archive and SHA-256 from https://developer.android.com/studio.
        tools_build=15859902
        tools_sha256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
        tools_destination="$sdk_dir/cmdline-tools/latest"
        [[ ! -e "$tools_destination" ]] || die "Incomplete tools installation at $tools_destination; repair it or set SDKMANAGER."
        log "Downloading Android SDK command-line tools"
        curl --fail --location --retry 3 --connect-timeout 30 \
            "https://dl.google.com/android/repository/commandlinetools-linux-${tools_build}_latest.zip" \
            --output "$temp_dir/tools.zip"
        printf '%s  %s\n' "$tools_sha256" "$temp_dir/tools.zip" | sha256sum --check --status \
            || die "Command-line tools checksum mismatch; download was not installed."
        unzip -q "$temp_dir/tools.zip" -d "$temp_dir/tools"
        mkdir -p "$sdk_dir/cmdline-tools"
        mv "$temp_dir/tools/cmdline-tools" "$tools_destination"
        sdkmanager="$tools_destination/bin/sdkmanager"
    fi

    # Locally installed packages whose package.xml uses the newer repository2/04
    # schema make cmdline-tools 20.x print, once per run:
    #   "This version only understands SDK XML versions up to 3 but an SDK XML
    #    file of version 4 was encountered. ..."
    # That is an upstream sdklib limitation, not a build problem, so drop only
    # that line. sed (rather than grep -v) keeps exit statuses usable: it always
    # succeeds, so pipefail can still report a failing sdkmanager.
    sdk_noise_filter='/This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered/d'

    run_sdkmanager() {
        if "$accept_licenses"; then
            # yes may get SIGPIPE after sdkmanager exits. Preserve sdkmanager's
            # exit code, rather than letting pipefail turn success into exit 141.
            (set +o pipefail
             yes | "$sdkmanager" "--sdk_root=$sdk_dir" "$@" 2>&1 | sed "$sdk_noise_filter"
             exit "${PIPESTATUS[1]}")
        else
            "$sdkmanager" "--sdk_root=$sdk_dir" "$@" 2>&1 | sed "$sdk_noise_filter"
        fi
    }

    read_gradle_setting() {
        local value
        value="$(sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"?([0-9.]+)\"?[[:space:]]*(\/\/.*)?$/\1/p" app/build.gradle.kts)"
        [[ -n "$value" && "$value" != *$'\n'* ]] || die "Cannot read $1 from app/build.gradle.kts. Update build.sh for the new configuration."
        printf '%s\n' "$value"
    }
    compile_sdk="$(read_gradle_setting compileSdk)"
    build_tools="$(read_gradle_setting buildToolsVersion)"
    ndk_version="$(read_gradle_setting ndkVersion)"
    cmake_version="$(sed -nE 's/^[[:space:]]*cmake_minimum_required\(VERSION ([0-9.]+)\).*/\1/p' app/src/main/jni/CMakeLists.txt)"
    [[ -n "$cmake_version" ]] || die "Cannot read the required CMake version."

    log "Checking available SDK packages"
    "$sdkmanager" "--sdk_root=$sdk_dir" --list 2>&1 | sed "$sdk_noise_filter" >"$temp_dir/packages.txt"
    # New SDKs use names such as android-37.0; older SDKs use android-36.
    platform_package=""
    for candidate in "platforms;android-$compile_sdk" "platforms;android-$compile_sdk.0"; do
        if awk -F '|' -v package="$candidate" '{gsub(/^[ \t]+|[ \t]+$/, "", $1); if ($1 == package) found=1} END {exit !found}' "$temp_dir/packages.txt"; then
            platform_package="$candidate"
            break
        fi
    done
    [[ -n "$platform_package" ]] || die "Android platform $compile_sdk was not listed by sdkmanager. Check connectivity and SDK repository availability."

    log "Checking Android SDK licenses"
    run_sdkmanager --licenses
    packages=("$platform_package" "build-tools;$build_tools" "ndk;$ndk_version" "cmake;$cmake_version" "platform-tools")
    log "Installing required SDK packages: ${packages[*]}"
    run_sdkmanager --install "${packages[@]}"
    # sdkmanager can return success when licenses were declined. Do not start
    # Gradle until the requested packages really exist.
    for package in "${packages[@]}"; do
        [[ -f "$sdk_dir/${package//;/\/}/source.properties" ]] || die "Package $package is still missing. Rerun with --setup-sdk and accept its license (or add --accept-licenses)."
    done
    cleanup
    temp_dir=""
fi

if [[ ! -f submodule/miuix/settings.gradle.kts ]]; then
    require_command git
    log "Initializing the miuix submodule"
    git submodule update --init --recursive -- submodule/miuix
fi

# miuix is included as a composite build and must resolve the same AGP/Kotlin as
# the main project; a mismatch breaks the Gradle build. miuix's libs.versions.toml
# is upstream-locked (kept identical to upstream), so it is the source of truth
# and the main project's versions must match it. Fail fast on any drift so the
# mismatch is caught before Gradle logs a confusing plugin-resolution error.
check_toolkit_versions() {
    local main_toml="$project_dir/gradle/libs.versions.toml"
    local miuix_toml="$project_dir/submodule/miuix/gradle/libs.versions.toml"
    [[ -f "$main_toml" && -f "$miuix_toml" ]] || return 0
    local key main_val miuix_val
    local toolkit_versions=()
    for key in agp kotlin; do
        main_val="$(sed -nE "s/^[[:space:]]*$key[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" "$main_toml" | head -n1)"
        miuix_val="$(sed -nE "s/^[[:space:]]*$key[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" "$miuix_toml" | head -n1)"
        [[ -n "$main_val" && -n "$miuix_val" ]] || die "Could not read '$key' from a version catalog (main='$main_val' miuix='$miuix_val')."
        if [[ "$main_val" != "$miuix_val" ]]; then
            die "Toolkit version mismatch for '$key': main=$main_val vs miuix=$miuix_val. Keep gradle/libs.versions.toml in sync with submodule/miuix/gradle/libs.versions.toml (miuix is the source of truth)."
        fi
        toolkit_versions+=("$key=$main_val")
    done
    log "Toolkit versions match miuix: ${toolkit_versions[*]}"
}
check_toolkit_versions
bash "$project_dir/scripts/prepare-miuix.sh"

apk_tasks=()
if "$user_gradle_args"; then
    apk_tasks=("${gradle_args[@]}")
else
    apk_tasks=(assembleDebug)
fi
# Keep both ARM architectures in normal Android builds. Respect either Gradle
# spelling of an explicit project-property override; desktop arguments stay separate.
abi_override=false
for ((arg_index = 0; arg_index < ${#apk_tasks[@]}; arg_index++)); do
    case "${apk_tasks[arg_index]}" in
        -PabiList=*|--project-prop=abiList=*) abi_override=true ;;
        -P|--project-prop)
            if [[ ${apk_tasks[arg_index + 1]:-} == abiList=* ]]; then
                abi_override=true
            fi
            ;;
    esac
done
if ! "$abi_override"; then
    apk_tasks+=(-PabiList=arm64-v8a,armeabi-v7a)
fi
log "Running Gradle: ${apk_tasks[*]}"
# The upstream wrapper is not executable in every checkout.
bash "$project_dir/gradlew" "${apk_tasks[@]}"

if [[ -d "$project_dir/app/build/outputs/apk/debug" ]]; then
    log "Available debug APKs:"
    find "$project_dir/app/build/outputs/apk/debug" -maxdepth 1 -type f -name '*.apk' -print | sort
fi

if "$install_requested"; then
    if "$target_apk"; then
        install_apk
    fi
    if "$target_deb"; then
        if "$user_gradle_args"; then
            build_desktop "${gradle_args[@]}"
        else
            build_desktop
        fi
    fi
fi
