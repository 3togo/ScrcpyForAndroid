package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionKeepAliveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun tearDown() { scope.cancel() }

    @Test fun continuesWithoutReconnectWhenStreamingAlive() {
        var reconnects = 0
        val keepAlive = ConnectionKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 20,
            isConnected = { true },
            keepAliveCheck = { true },
            reconnect = { reconnects++ },
        ))
        // Give the loop time to tick a few times; if streaming stays alive it must never reconnect.
        Thread.sleep(80)
        keepAlive.stop()
        assertEquals(0, reconnects)
    }

    @Test fun reconnectsOnceWhenStreamingDropsThenStops() {
        var reconnects = 0
        var successes = 0
        val keepAlive = ConnectionKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 20,
            isConnected = { reconnects == 0 },
            keepAliveCheck = { false },
            reconnect = { reconnects++ },
            onReconnectSuccess = { successes++ },
        ))
        Thread.sleep(80)
        keepAlive.stop()
        assertEquals(1, reconnects)
        assertEquals(1, successes)
    }

    @Test fun reportsFailureAndStopsWhenReconnectThrows() {
        var reconnects = 0
        var failures = 0
        val keepAlive = ConnectionKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 20,
            isConnected = { reconnects == 0 },
            keepAliveCheck = { false },
            reconnect = { reconnects++; error("boom") },
            onReconnectFailure = { failures++ },
        ))
        Thread.sleep(80)
        keepAlive.stop()
        assertEquals(1, reconnects)
        assertEquals(1, failures)
    }

    @Test fun breaksWithoutReconnectWhenAutoReconnectDisabled() {
        var reconnects = 0
        val keepAlive = ConnectionKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 20,
            isConnected = { true },
            keepAliveCheck = { false },
            shouldAutoReconnect = { false },
            reconnect = { reconnects++ },
        ))
        Thread.sleep(80)
        keepAlive.stop()
        assertEquals(0, reconnects)
    }

    @Test fun stopCancelsLoopAndPreventsFurtherReconnects() {
        var reconnects = 0
        val keepAlive = ConnectionKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 20,
            isConnected = { true },
            keepAliveCheck = { false },
            reconnect = { reconnects++ },
        ))
        Thread.sleep(60)
        keepAlive.stop()
        val afterStop = reconnects
        Thread.sleep(80)
        assertEquals(afterStop, reconnects)
    }
}
