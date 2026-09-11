package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy

/**
 * Shared teardown for a live scrcpy session, used by both the TV backend and the phone controller.
 * Stops the scrcpy client, disconnects the ADB coordinator, clears the session-scoped connection
 * context and releases the screen-on lock. Callers that also hold a USB session are responsible for
 * tearing that down separately (the phone path does so via [UsbAdbSession.disconnect]).
 */
internal suspend fun teardownScrcpySession(
    coordinator: DeviceAdbConnectionCoordinator,
    scrcpy: Scrcpy,
) {
    scrcpy.stop()
    coordinator.disconnect()
    AppRuntime.clearConnectionContext()
    AppScreenOn.release()
}
