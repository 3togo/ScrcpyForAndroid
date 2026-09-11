package io.github.miuzarte.scrcpyforandroid.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BundleSyncDelegateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun tearDown() { scope.cancel() }

    @Test fun updatePropagatesToValueImmediately() {
        val shared = MutableStateFlow("a")
        val delegate = BundleSyncDelegate(shared, { }, scope, delayMs = 50)
        delegate.start()
        delegate.update { "b" }
        assertEquals("b", delegate.value.value)
    }

    @Test fun externalSharedFlowChangeSyncsIntoLocalValue() {
        val shared = MutableStateFlow("a")
        val delegate = BundleSyncDelegate(shared, { shared.value = it }, scope, delayMs = 50)
        delegate.start()
        delegate.update { "local" }
        Thread.sleep(150) // let the debounce persist the local edit into the upstream flow
        shared.value = "external"
        Thread.sleep(50)  // let the upstream collector propagate the external change back
        assertEquals("external", delegate.value.value)
    }

    @Test fun debounceCoalescesRapidUpdatesIntoSingleSave() {
        val shared = MutableStateFlow("a")
        val saved = mutableListOf<String>()
        val delegate = BundleSyncDelegate(shared, { saved.add(it); shared.value = it }, scope, delayMs = 50)
        delegate.start()
        delegate.update { "b" }
        delegate.update { "c" }
        delegate.update { "d" }
        Thread.sleep(150) // wait past the debounce window
        assertEquals(listOf("d"), saved)
    }

    @Test fun flushSavesPendingValueAndDebounceDoesNotDoubleSave() {
        val shared = MutableStateFlow("a")
        val saved = mutableListOf<String>()
        val delegate = BundleSyncDelegate(shared, { saved.add(it); shared.value = it }, scope, delayMs = 50)
        delegate.start()
        delegate.update { "z" }
        runBlocking { delegate.flush() } // synchronous save before the debounce timer elapses
        assertEquals(listOf("z"), saved)
        Thread.sleep(150) // the debounce would now fire, but the upstream already equals the value
        assertEquals(listOf("z"), saved)
    }
}
