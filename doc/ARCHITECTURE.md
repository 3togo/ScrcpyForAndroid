# Architecture

This document describes how the `app` module is laid out, which invariants must not
break, and the rules that keep a refactor from destroying upstream syncability.
Read it before moving, splitting, or renaming anything under `app/src/main`.

## Upstream constraint (read first)

ScrCaster is a fork of
[`Miuzarte/ScrcpyForAndroid`](https://github.com/Miuzarte/ScrcpyForAndroid.git)
and keeps merging it (see `./sync-upstream.sh`, `CONTRIBUTING.md`). At the time of
writing, **138 of the 163 source files in `app/src/main` are upstream-owned**; 25 are
ScrCaster-original (`connection/`, the `Tv*.kt` files, and a handful of others).

An earlier commit, `dec61d9` "Rename project to ScrCaster and modernize structure",
moved and renamed packages. It had to be reverted by `05f649a` "Keep Android source
packages aligned with upstream", because rename/delete conflicts make every later
upstream merge manual labour. That history is the reason for these rules:

1. Never rename, move, or delete an upstream-owned file, and never change
   `android.namespace`. New code goes into **new** files; the old file stays at its
   path as a thin shell that delegates. Content conflicts are acceptable,
   modify/delete conflicts are not.
2. `com/termux/**` is vendored terminal code. Leave it alone, including its logging
   and error conventions.
3. JNI entry points under `app/src/main/jni` and the `external fun` declarations that
   mirror them are frozen.
4. No dependency-injection framework (KSP/annotation processors), no repository-wide
   formatter. Both would touch every upstream file.
5. Adding a file is always safe. Prefer `graph/`, `domain/`, `options/`, `session/`,
   `shell/`, `ui/screen/`, `ui/component/`, `util/` for new code.

## Layers

```
ui/ pages/ widgets/ scaffolds/        Compose screens and components
  |  reads StateFlow, sends commands; never touches DataStore, sockets, or MediaCodec
  v
pages/*ViewModel.kt                   presentation state for one screen
  v
domain/ graph/                        object graph, connection state machine, pure models
  v
scrcpy/ options/ services/            session facade, option pipeline, recording, files
  v
nativecore/                           adb wire protocol, mDNS, MediaCodec, USB, JNI facade
```

Dependencies point downwards only. Known violations being removed by the ongoing
refactor: `storage/Settings.kt` composing UI state, `services/AppRuntime` calling into
`MainActivity`, `pages/DeviceTabViewModel` running connection polling loops.

### Object graph

`AppGraph` (in `graph/`) is the only place that builds long-lived objects. It is
created by the `Application` and reached from Compose through `LocalAppGraph`.
`AppRuntime` and `Storage` survive only as forwarding shims so that upstream call
sites keep compiling; nothing new should use them.

Rules:

- No top-level `object` holding mutable state.
- A running `Scrcpy` session is not rebuilt by recomposition; configuration changes
  call `ScrcpySessionHolder.updateServerConfig(...)`.
- ViewModels receive their collaborators through a factory built from `AppGraph`, not
  through globals.

### Option pipeline

One option must have exactly one definition. `options/ScrcpyOption.kt` is that
definition: DataStore key, default, codec, server argument name, and UI metadata. The
three representations are derived from it:

```
ScrcpyOptions.Bundle  --toClientOptions()-->  ClientOptions  --toServerParams()-->  ServerParams  --toList()-->  server command line
       (persisted)                                  (validated)                          (ordered args)
```

`ClientOptions.fix()` drops mutually exclusive values, `validate()` rejects
impossible combinations, and `ServerParams.toList(preview = true)` produces the
argument list. Note that several `// to server` markers in `ClientOptions` are
stale: `turnScreenOff`, `gamepad`, `mouseHover`, `disableScreensaver`, `fullscreen`,
`keyInjectMode`, `renderFit`, `aspectRatio`, `startApp` and `killAdbOnClose` never
reach the server (they are handled by local input injection instead). The client-only
list in `ClientOptionsPipelineTest` pins this down.

### Connection and session

`domain/ConnectionMachine.kt` owns the connection lifecycle:

- states `Idle / Connecting / Connected / Reconnecting / Failed`,
- commands `Connect / Disconnect / SwitchTarget / TickKeepAlive / TickHealth / SessionDied`,
- one `AdbTransport` abstraction with a wireless-LAN and a USB implementation.

Timers and retries live in the machine on `AppGraph.scope`; screens and ViewModels only
observe `StateFlow`. Keep-alive polling mechanics are shared through
`connection/ConnectionKeepAlive.kt`, whose notify dispatcher is injectable so the loop
is testable off-device.

## Invariants that are machine-checked

| Contract | Test |
| --- | --- |
| Server argument list, per option combination | `options/ClientOptionsPipelineTest` + `app/src/test/resources/options/*.txt` |
| Shell output parsing (`encoders`, `displays`, `cameras`, `apps`, `recent tasks`) | `shell/ShellParsersGoldenTest` |
| Aspect-ratio and crop math shared with the desktop frontend | `core` `AspectRatioTest`, `scrcpy/AspectRatioPresetsTest` |
| Recording filename template expansion | `services/RecordFilenameTemplateTest` |
| ADB wireless pairing QR format | `connection/QrPairingPayloadTest` |
| Keep-alive / reconnect loop | `connection/ConnectionKeepAliveTest`, `connection/ConnectionControllerTest` |
| Bundle save/load round trip and debounced flush | `storage/BundleSyncDelegateTest` |

Goldens are frozen output text, not code. Regenerate them deliberately:

```sh
SCRCASTER_UPDATE_GOLDEN=1 ./gradlew :app:testDebugUnitTest --tests '*ClientOptionsPipelineTest*'
```

Never regenerate just to make a failing assertion pass; a changed golden is a behavior
change and belongs in its own commit with an explanation.

## Verification gate

Every change must pass the same check the PR workflow runs
(`.github/workflows/pr-check.yml`):

```sh
bash build.sh clean assembleDebug testDebugUnitTest
./gradlew :core:test          # shared aspect-ratio math
```

Then install and smoke-test on a device: wireless connect, USB connect, start stream,
touch and virtual buttons, clipboard both directions, recording (mp4 / wav / aac), file
manager, terminal, lock-screen autofill, PiP, TV receiver, dark mode and in-app language
switch.

## Conventions

- Kotlin style follows the surrounding file: 4-space indent, trailing commas in
  multi-line argument lists, `class Foo(` with the `:` supertype list on the next line.
- Logging goes through `util/AppLog.kt` with a per-class `TAG`; user-visible failures are
  reported through `UiEvents` (snackbar), never by swallowing a `runCatching`.
- Strings are resources (`R.string.*`), and every new string needs both `values/` and
  `values-zh/` entries.
- New files carry a KDoc block saying what they own and why they exist, so the next
  reader knows where to look.

## Refactor status

Tracked phase by phase in the approved plan; each phase ends green and shippable.

| Phase | Scope | State |
| --- | --- | --- |
| 0 | Baseline, goldens, characterization tests, this document | done |
| 1 | `AppGraph`, retire `AppRuntime` / `Storage` mutable state | pending |
| 2 | Option spec table, table-driven options UI | pending |
| 3 | `ConnectionMachine`, thin `DeviceTabViewModel` | pending |
| 4 | Split `Scrcpy.kt` into `session/`, `shell/`, `server/` | pending |
| 5 | Split mega composables, unify the three video surfaces | pending |
| 6 | Logging, duplicated helpers, error model, profile binding | pending |
| 7 | `:core` / `:desktop` tidy-up, docs | pending |
