# Linux desktop

The `desktop/` project provides a Linux device manager using Java Swing and the
native `adb` and `scrcpy` executables. Mirroring opens a separate scrcpy window
with keyboard/mouse control and clipboard synchronization. The existing Android
application continues to build independently.

## Requirements

- A Linux graphical session (X11 or Wayland with XWayland).
- Java 17 or newer, including the desktop libraries (not a headless-only JRE).
  Building requires a JDK 17 installation.
- Android platform-tools (`adb`) with wireless pairing support.
- Native scrcpy 4.0 or newer; development validation uses scrcpy 4.1.
  Releases before 4.0 have no `--render-fit`, so the Stretch fill mode is
  unavailable there.
  Follow the [official Linux installation instructions](https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md).

Both executables must be on `PATH`. To use specific installations, set `ADB` and
`SCRCPY` to executable paths, including paths containing spaces. These variables
accept a single executable, not a command with arguments. The selected `ADB` is
also passed to scrcpy. The Android APK's embedded server is not used: native
scrcpy supplies its matching server.

## Build and run

From the repository root:

```sh
bash gradlew -p desktop check installDist
./desktop/build/install/scrcpy-desktop/bin/scrcpy-desktop
```

For development, use `bash gradlew -p desktop run`. The standalone desktop build
does not configure the Android project and needs no Android SDK, NDK, or miuix
submodule. ZXing core generates pairing QR codes and JmDNS provides direct
multicast discovery. Both are bundled in the distribution; the first build
downloads the dependencies from Maven Central.

Create a portable application archive with:

```sh
bash gradlew -p desktop distTar
```

Extract `desktop/build/distributions/scrcpy-desktop-0.5.5.tar` and run
`bin/scrcpy-desktop` inside the extracted directory. Java, adb, and scrcpy are
runtime prerequisites; the archive does not bundle them.

### Debian / Ubuntu package

With a JDK 17 and `dpkg-deb` installed, run from the repository root:

```sh
./desktop/package-deb.sh
```

The script runs the desktop checks and builds the application before packaging.
No root privileges are needed to build. In an interactive terminal, the script
asks permission before running `sudo apt install` (or `apt install` as root).
Answering yes installs or updates the generated package and lets apt resolve any
missing runtime dependencies. Enter, no, or end-of-input skips installation;
the built package remains available. Non-interactive runs never install anything.
Use `--no-install` to suppress the prompt, or install later with:

```sh
sudo apt install ./desktop/build/distributions/scrcpy-desktop_0.5.5-1_all.deb
```

The package installs an application-menu
entry, icon, and `scrcpy-desktop` command. It bundles the Java libraries and
declares dependencies on a graphical Java 17+ runtime, adb, and scrcpy 4.0+.
Your configured apt repositories must provide those dependencies; an unmanaged
scrcpy installation in `/usr/local` does not satisfy apt's dependency tracking.
Many distributions still package scrcpy 3.x, so `apt install` can report
`scrcpy (>= 4.0)` as unsatisfiable. Install scrcpy 4.0+ from the official
instructions first, or bypass the check with
`sudo dpkg -i --force-depends scrcpy-desktop_VERSION_all.deb`.

Use `--skip-build` to package an existing `installDist`, or `--output-dir DIR`
to choose another output directory. Gradle flags follow `--`, for example
`./desktop/package-deb.sh -- --offline --no-daemon`. `DEB_VERSION` overrides the
default application version plus `-1`; `DEB_MAINTAINER='Name <email>'` replaces
the default local-builder identity. `GRADLE` may select a Gradle executable.
Run `./desktop/package-deb.sh --help` for all options.

## Connecting

- **QR pairing (Android 11+):** click **Pair with QR…** on the desktop. On the
  phone, open **Developer options → Wireless debugging → Pair device with QR
  code** and scan the displayed code. Keep both devices on the same network.
  The app discovers the matching pairing service, pairs, and connects using the
  phone's advertised connection port, then starts mirroring automatically using
  the current stream settings. If recording is enabled, choose the recording file
  when prompted. An existing mirrored session is kept running. If connection discovery fails after pairing,
  enter the connection address manually; pairing does not need to be repeated.
  Discovery expires after two minutes. **New QR code** creates fresh credentials;
  **Cancel**, Escape, or closing the dialog cancels the session. QR pairing falls
  back to direct IPv4 mDNS discovery if adb cannot find the service, including
  installations reporting `mdns daemon unavailable`. Allow multicast UDP 5353
  on the local network. No Avahi service or adb-server restart is required.
- **USB:** enable USB debugging, attach the device, authorize the computer on the
  Android device, and click **Refresh**. If the list reports `no permissions`,
  configure your distribution's Android USB/udev permissions.
- **Wireless (Android 11+):** enable Wireless debugging on Android. Open **Pair
  device with pairing code**, then enter that pairing address and six-digit code
  in **Pair…**. After pairing, enter the address from the main Wireless debugging
  screen and click **Connect**. Pairing and connection ports are different.
- **Existing TCP/IP debugging:** enter `host:port` and click **Connect**. IPv6
  addresses use `[address]:port`.
- **Discover:** lists the services reported by `adb mdns services` in Activity.
  Copy the appropriate connection or pairing address into the corresponding form.
  Availability depends on the installed adb's mDNS support and network.

Select an online device, choose stream settings, and click **Start mirroring**.
**Stop mirroring** terminates only the session launched by this app. Closing the
manager also stops its child processes; it does not kill the shared adb server.
**Disconnect** disconnects the selected network device, which also affects other
adb clients using that connection.

For fullscreen playback, **Crop to fill long edge** is the default fill mode.
Both crop-to-fill choices preserve aspect ratio by first center-cropping the phone
capture. **Crop to fill long edge** follows the phone orientation; when phone and
monitor orientations differ, the remaining bars are retained so portrait input is
never turned into landscape. **Crop to fill short edge** is an explicit cover mode:
it may trim enough of the source to match the monitor orientation and then fills the
whole desktop. The original **Crop** preference is migrated to the long-edge mode.
**Fit** keeps the complete frame and may show black bars; **Stretch** fills without
cropping and may distort the picture. A fixed **Aspect ratio** selection takes
priority over fullscreen crop-to-fill and remains aspect-preserving.

Enable **Record to file…** before starting to choose an MP4 or MKV destination.
Recording ends with the stream. For native window shortcuts and recording details,
see [scrcpy controls](https://github.com/Genymobile/scrcpy/blob/master/doc/control.md)
and [recording](https://github.com/Genymobile/scrcpy/blob/master/doc/recording.md).
**Send file…** copies a local file to an Android directory. **Receive file…**
copies an Android path into a local directory. Transfers may overwrite existing
files with matching names.

The last connection address and stream settings are saved using Java Preferences
(typically `~/.java/.userPrefs`). Pairing codes are never saved or placed in
process arguments; adb manages its own device authorization keys.

## Current scope and validation

This is a Linux frontend to native scrcpy, not a port of the Android Compose UI.
It supports one mirrored session at a time. Android-specific PiP, biometric
password storage, virtual-button overlays, profile import, embedded terminal,
and automatic mDNS reconnection are not implemented in the desktop interface.

`check` runs backend integration tests using a fake executable:
endpoint validation, device parsing, literal argument handling, stream options,
pairing via standard input, error exits, timeouts, and child-process cleanup.
QR tests decode the generated image and exercise exact service matching,
automatic connection, quoted-password compatibility, false success responses,
discovery timeout, and cancellation using fake adb commands. `guiSmoke` opens,
regenerates, and closes the QR dialog without contacting a device.
It never pairs with or changes a real device. Live video/audio, clipboard,
recording playback, and file transfers still require testing with an Android
device and the installed native scrcpy version.
