package io.github.miuzarte.scrcpyforandroid.connection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the shared keep-alive loop.
 *
 * The loop runs on a background dispatcher, so every expectation waits on a latch instead of a
 * wall-clock sleep, and the counters are atomic: a timed assertion here would be flaky and would
 * also race against the loop's own thread.
 */
class ConnectionKeepAliveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() { scope.cancel() }

    // Poll fast; notify inline so the callbacks land before their latches release the test.
    private fun createKeepAlive() = ConnectionKeepAlive(Dispatchers.Unconfined, Dispatchers.Unconfined)

    @Test fun continuesWithoutReconnectWhenStreamingAlive() {
        val reconnects = AtomicInteger()
        val ticks = CountDownLatch(3)
        val keepAlive = createKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 10,
            isConnected = { true },
            keepAliveCheck = { ticks.countDown(); true },
            reconnect = { reconnects.incrementAndGet() },
        ))
        // Give the loop time to tick a few times; if streaming stays alive it must never reconnect.
        assertTrue("keep-alive loop never polled three times", ticks.await(5, TimeUnit.SECONDS))
        keepAlive.stop()
        assertEquals(0, reconnects.get())
    }

    @Test fun reconnectsOnceWhenStreamingDropsThenStops() {
        val reconnects = AtomicInteger()
        val succeeded = CountDownLatch(1)
        val keepAlive = createKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 10,
            isConnected = { reconnects.get() == 0 },
            keepAliveCheck = { false },
            reconnect = { reconnects.incrementAndGet() },
            onReconnectSuccess = { succeeded.countDown() },
        ))
        assertTrue("reconnect never ran", succeeded.await(5, TimeUnit.SECONDS))
        keepAlive.stop()
        assertEquals(1, reconnects.get())
    }

    @Test fun reportsFailureAndStopsWhenReconnectThrows() {
        val reconnects = AtomicInteger()
        val failed = CountDownLatch(1)
        val keepAlive = createKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 10,
            isConnected = { reconnects.get() == 0 },
            keepAliveCheck = { false },
            reconnect = {
                reconnects.incrementAndGet()
                error("boom")
            },
            onReconnectFailure = { failed.countDown() },
        ))
        assertTrue("failure was never reported", failed.await(5, TimeUnit.SECONDS))
        keepAlive.stop()
        assertEquals(1, reconnects.get())
    }

    @Test fun breaksWithoutReconnectWhenAutoReconnectDisabled() {
        val reconnects = AtomicInteger()
        val polled = CountDownLatch(1)
        val keepAlive = createKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 10,
            isConnected = { true },
            keepAliveCheck = { polled.countDown(); false },
            shouldAutoReconnect = { false },
            reconnect = { reconnects.incrementAndGet() },
        ))
        assertTrue("keep-alive loop never polled", polled.await(5, TimeUnit.SECONDS))
        keepAlive.stop()
        assertEquals(0, reconnects.get())
    }

    @Test fun stopCancelsLoopAndPreventsFurtherReconnects() {
        val reconnects = AtomicInteger()
        val reconnected = CountDownLatch(1)
        val keepAlive = createKeepAlive()
        keepAlive.start(scope, KeepAlivePolicy(
            intervalMs = 10,
            isConnected = { true },
            keepAliveCheck = { false },
            reconnect = {
                reconnects.incrementAndGet()
                reconnected.countDown()
            },
        ))
        assertTrue("keep-alive never attempted a reconnect", reconnected.await(5, TimeUnit.SECONDS))
        keepAlive.stop()
        val afterStop = reconnects.get()
        // The loop polls every 10ms, so a surviving loop would show up immediately.
        Thread.sleep(200)
        assertEquals(afterStop, reconnects.get())
        assertTrue("loop stayed active after stop()", !keepAlive.isActive)
    }
}
