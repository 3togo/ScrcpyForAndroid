#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

usage() {
    cat <<'EOF'
Usage: ./build.sh [--accept-licenses] [--skip-sdk-setup] [Gradle arguments...]
       ./build.sh --desktop [Gradle arguments...]

By default, prepare the Android SDK and build debug APKs (assembleDebug).
Missing SDK command-line tools, platform, build-tools, NDK and CMake are installed.
SDK licenses are displayed for interactive acceptance before building.

  --accept-licenses  Answer yes to Android SDK license prompts (for CI/SSH).
                    Use only if you agree to the Android SDK license terms.
  --skip-sdk-setup   Use an already prepared SDK; useful for offline builds.
  --desktop         Build the Linux desktop app without Android SDK setup.
                    Default tasks: check installDist.
  -h, --help        Show this help.
  --                Pass all remaining arguments directly to Gradle.

Examples:
  ./build.sh
  ./build.sh --accept-licenses
  ./build.sh --accept-licenses clean assembleDebug -PabiList=arm64-v8a
  ./build.sh --skip-sdk-setup assembleDebug --offline
  ./build.sh --desktop

SDK location: local.properties sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT,
then an existing SDK in ~/Android/Sdk, ~/Android or ~/.android.
A fresh SDK defaults to ~/Android/Sdk. SDKMANAGER can select a specific tool.
Requires Bash, Java 17+ (JDK 21 recommended), and Git for missing submodules.
Automatic command-line tools download requires Linux x86_64, curl, unzip and
sha256sum. Other hosts must provide sdkmanager themselves.
EOF
}

die() { printf 'build.sh: %s\n' "$*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "Required command missing: $1"; }

accept_licenses=false
skip_sdk_setup=false
desktop=false
gradle_args=()
while (($#)); do
    case "$1" in
        --accept-licenses) accept_licenses=true ;;
        --skip-sdk-setup) skip_sdk_setup=true ;;
        --desktop) desktop=true ;;
        -h|--help) usage; exit 0 ;;
        --) shift; gradle_args+=("$@"); break ;;
        *) gradle_args+=("$1") ;;
    esac
    shift
done

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$java_bin" >/dev/null 2>&1 || die "Install JDK 21 or set JAVA_HOME to an installed JDK."
java_version_output="$("$java_bin" -version 2>&1)"
java_major="$(sed -nE 's/.*version "([0-9]+).*/\1/p' <<<"$java_version_output")"
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || ((java_major < 17)); then
    die "Java 17+ is required; install JDK 21 or fix JAVA_HOME."
fi

if "$desktop"; then
    ((${#gradle_args[@]})) || gradle_args=(check installDist)
    exec bash "$project_dir/gradlew" -p "$project_dir/desktop" "${gradle_args[@]}"
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

    run_sdkmanager() {
        if "$accept_licenses"; then
            # yes may get SIGPIPE after sdkmanager exits. Preserve sdkmanager's
            # exit code, rather than letting pipefail turn success into exit 141.
            (set +o pipefail; yes | "$sdkmanager" "--sdk_root=$sdk_dir" "$@")
        else
            "$sdkmanager" "--sdk_root=$sdk_dir" "$@"
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
    "$sdkmanager" "--sdk_root=$sdk_dir" --list >"$temp_dir/packages.txt"
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
        [[ -f "$sdk_dir/${package//;/\/}/source.properties" ]] || die "Package $package is still missing. Accept its license (or use --accept-licenses) and rerun."
    done
    cleanup
    temp_dir=""
fi

if [[ ! -f submodule/miuix/settings.gradle.kts ]]; then
    require_command git
    log "Initializing the miuix submodule"
    git submodule update --init --recursive -- submodule/miuix
fi

((${#gradle_args[@]})) || gradle_args=(assembleDebug)
log "Running Gradle: ${gradle_args[*]}"
# The upstream wrapper is not executable in every checkout.
exec bash "$project_dir/gradlew" "${gradle_args[@]}"
