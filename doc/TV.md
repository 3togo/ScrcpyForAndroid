# Android TV receiver

The TV launcher opens the shared Compose connection screen in remote mode. The
regular launcher also selects this screen when Android reports a television/Leanback
device. The home groups **Devices** and **Settings**, uses the same saved color
palette and language as the phone interface, and displays remembered phone addresses.
The existing phone interface remains the phone entry point during this first
stage of the migration.

## Shared connection architecture

- `connection/ConnectionController.kt` owns pairing, connection, cancellation,
  errors, multi-endpoint persistence, refresh reconciliation, and playback events. It has no Activity
  or UI dependencies; the backend and preference store are injectable.
- `connection/AndroidConnectionBackend.kt` adapts the existing ADB coordinator,
  mDNS discovery, scrcpy session and screen-on policy. The preference adapter keeps
  the existing `tv_connection` file and keys, preserving installed TV settings.
- `connection/ConnectionScreen.kt` owns Compose presentation. Remote mode adds
  explicit Up/Down traversal and focus restoration; the layout stacks on smaller
  widths. It shares the phone's Miuix palette through Material controls that support
  keyboard focus.
- `TvActivity` is the launcher/lifecycle adapter. It requests network permission,
  handles Back, and opens the existing `StreamActivity` in receiver mode.

The next phone stage can adopt this controller and screen without duplicating the
pairing flow. This stage does not replace the phone's device manager, terminal,
file manager, or profile editor with the receiver home.

## Connect a phone

1. Keep the phone and TV on the same local network.
2. Select **Connect with QR code**. On the phone open Developer
   options → Wireless debugging → Pair device with QR code, then scan the TV.
   Leave the dialog open while pairing and automatic connection complete.
3. Open your video app on the phone. The TV preserves the source aspect ratio.

**Other ways to connect** contains two distinct flows:
- **Use a pairing code**: copy the complete `IP address:port` and six-digit code
  from the phone's pairing-code popup. The receiver pairs and discovers the
  separate connection endpoint automatically.
- **Connect by address**: for a phone already authorized with this receiver,
  enter `IP address:port` from the main Wireless debugging screen. This also
  supports a USB-authorized legacy `phone-IP:5555` endpoint.

The home screen remembers successfully connected phones, lists the most recent first,
and reconnects the selected phone on a fresh launch. Each entry can be connected or
forgotten. **Refresh devices** probes the saved address and uses mDNS to recover a
rotated wireless-debugging port; unavailable entries are removed with a warning.
Existing single-phone preferences migrate into this list. Failed connection attempts
do not overwrite it. No address fields are shown for QR setup. A failed connection-port
lookup opens the address form.

**Playback settings** groups audio, fullscreen fill and aspect ratio, including
custom ratios. These values persist immediately and apply on the next connection.
Existing playback-menu picture controls remain available during a session.

## Remote controls

- All setup dialogs use large buttons with explicit Up/Down navigation and a
  visible focus highlight. Closing a dialog restores focus to its home action.
  OK activates buttons or opens the keyboard. Keyboard
  Done moves to the next control. Back closes a dialog.
- Playback: arrows, OK/Enter, and media playback keys are forwarded to the phone.
  Whether these navigate the phone app depends on that app's keyboard support.
- Back or Menu: opens local receiver controls, including Resume, Send Back to
  phone, and Disconnect. Back is never silently trapped by the remote phone.
- TV volume keys remain local.

Certificate rejection opens the QR pairing dialog automatically. Other pairing
and connection failures are shown on screen and can be retried. Phone connection
addresses, the selected phone, and audio selection are remembered. Picture-in-picture
and source-driven portrait rotation are disabled on TVs.

## Validation

Unit tests are the acceptance baseline for device reconciliation, multi-device
persistence, rotated-port refresh, removal warnings, and deterministic D-pad focus
order. A physical Android TV check is optional compatibility validation for launcher
discovery, vendor-specific focus rendering, video/audio hardware behavior, and remote
key delivery; it is not a substitute for those tests. Do not interpret a successful APK
build as proof that a source app handles D-pad input.

## Testing strategy for the Compose migration

Run the offline unit suite for every change. It covers deterministic connection,
pairing, persistence, cancellation, and remote-focus policy without requiring a TV,
phone, network, or ADB. Device checks are a short release-readiness smoke test for
Android input, keyboard, and receiver playback; they are not the default feedback
loop for UI logic.

## Automated checks

```sh
./build.sh --skip-sdk-setup :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PabiList=armeabi-v7a
# Install the resulting app and androidTest APK on the target TV, then:
# Run methods separately on boxes that kill the test process between activities.
for method in manualFieldsLeadToAllActionsAndBack playbackSettingsAndCustomRatioAreReachable validAddressAndKeyboardDoneConnectTheEnteredEndpoint invalidAddressLeavesFormOpenForCorrection qrDialogCanReturnToHomeAction; do
  adb -s DEVICE shell am instrument -w -e class "io.github.miuzarte.scrcpyforandroid.TvNavigationTest#$method" io.github.togo3.scrcaster.tvdebug.test/androidx.test.runner.AndroidJUnitRunner
done
```

Controller tests cover pairing versus connection ports, missing advertisements,
invalid input, cancellation and late discovery, failed-connection cleanup,
certificate rejection, QR retry, resume, and saved preferences. Compose tests use
an isolated fake backend and do not modify the receiver's saved phone or pairing
keys. They exercise D-pad focus, manual forms, QR actions, and custom ratio input.
The QR focus order itself is also a JVM test, avoiding a transient Android dialog
window-focus assertion.
On the Skyworth Q601B (Android 10, armeabi-v7a), the migrated debug app was
installed over the existing receiver without changing its saved preferences.
Direct D-pad checks confirmed form traversal and OK-to-open-keyboard behavior.
Physical HDMI-CEC delivery and a new phone pairing approval still need hardware
validation.

## Picture proportions

Back/Menu → Picture proportions chooses phone display dimensions (default) or
video stream dimensions. The phone setting reads the effective `wm size` and
uses the stream orientation; it falls back to stream dimensions if unavailable.
Both modes fit within the TV with black bars as needed, and preserve pointer
coordinate mapping. The choice is remembered. If the app itself stretches the
video inside the phone screen, changing the receiver ratio alone cannot identify
and correct that video rectangle independently.

## Phone control mappings

During playback, Back/Menu → **Phone control button mappings** assigns a spare
remote button to phone Back, Home, Recents, play/pause, pointer mode, or drag.
Choose an action, then press and release a button. Assignments persist on the
receiver and only apply inside this app during phone playback. Reset mappings
restores default handling. Arrows, OK, Back/Menu, Home, and volume stay reserved.

**Use pointer mode** enables a cursor: arrows move it, holding an arrow speeds
it up, and OK taps once. **Start drag / swipe at cursor** begins a touch gesture;
arrows move the touch and OK releases it. Opening receiver controls also releases
the gesture. Cursor coordinates follow the displayed video in either picture
proportion mode. Focus loss and stream-size changes release active input.

## Lessons learned from real boxes

### Separate discovery from physical device classification

ADB discovery found an Allwinner ZC-40M running Android 7.1.1, but its firmware
reported `ro.build.characteristics=tablet`, normal UI mode, and no TV/Leanback
features. The user confirmed that this device was a TV box. Initially counting
only devices with TV flags incorrectly reported zero TVs despite finding its
working ADB connection.

The scanner now lists every ADB-accessible device. Manufacturer/model, Android
version, firmware characteristics, and TV flags are evidence, not an authoritative
physical-device classification. Missing TV flags must not exclude a device.
User labels are explicitly user-provided and do not pretend to be automatic
hardware detection. Built-in televisions and external boxes may share TV flags.

Use `scripts/find-adb-tvs.py` for local IPv4 discovery. It combines port 5555
(or explicitly selected ports), mDNS connection advertisements, and existing ADB
connections. Pairing ports are not connection ports. Unauthorized devices cannot
be inspected until authorized; unadvertised services on other ports can be missed.
A scan is a point-in-time result, not proof that an absent device does not exist.

### Check the running Android ABI, not the chipset marketing name

The connected SkyworthDigitalRT Q601B reported Android 10 and
`ro.product.cpu.abilist=armeabi-v7a,armeabi`, with primary ABI `armeabi-v7a`.
The ARM64 APK was therefore the wrong deliverable for this box. Build a 32-bit
ARM APK even if the underlying chipset might support a different execution mode.

Check each target before choosing an APK:

```sh
adb -s DEVICE shell getprop ro.product.cpu.abilist
adb -s DEVICE shell getprop ro.product.cpu.abi
adb -s DEVICE shell getprop ro.build.version.release
./build.sh --skip-sdk-setup assembleDebug -PabiList=armeabi-v7a
```

The output follows
`app/build/outputs/apk/debug/ScrCaster-v<version>-armeabi-v7a-debug-<yyyyMMdd-HHmmss>.apk`.
Here, legacy `armeabi` in the reported ABI list does not require a separate legacy
build: the device explicitly supports `armeabi-v7a`. Inspect packaged `lib/`
directories to verify the artifact, rather than relying only on its filename.

ABI compatibility and Android-version compatibility are separate checks. The
minimum Android version remains 8 (API 26), so the Android 7.1.1 Allwinner box
cannot install this APK even with the correct CPU ABI.

### Keep the two remote-control layers separate

1. **TV remote → TV box:** the TV and box system handle HDMI-CEC. A normal
   receiver APK does not enable CEC across TV brands or provide a global remapper.
2. **Box input → phone:** this APK maps delivered Android input to scrcpy key
   and touch commands, only while controlling the phone.

CEC configuration APIs are privileged system APIs; adding a Leanback launcher
does not grant that access. See the [Android HDMI-CEC service documentation](https://source.android.com/docs/devices/tv/hdmi-cec).
A separate global remapper would be a different product with different permission
requirements. An app cannot assign a button that the TV/system never delivers.

Launching playback from the TV interface now explicitly selects receiver mode,
even when supported hardware runs firmware without TV flags. This avoids repeating
the scanner's classification mistake in playback behavior.

### Distinguish tested code from end-to-end hardware validation

The phone-control implementation passed 23 Android unit tests, including mapping
persistence, reassignment/reset, reserved keys, pointer behavior, and input release.
Both ARM64 and armeabi-v7a debug builds succeeded; the 32-bit artifact's native
libraries were verified as armeabi-v7a. These checks do not establish physical
remote/CEC interoperability or correct behavior in every source phone app.

On each real TV/box combination, test button delivery, held-button repeats,
receiver menu escape, phone navigation, pointer taps/drags, rotation, reconnection,
and both aspect settings. Verify mappings do not affect other apps on the box.
