package io.github.miuzarte.scrcpyforandroid.nativecore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class AdbEndpointReachabilityTest {

    @Test
    fun acceptsListeningPortAndRejectsItsStaleRecordAfterClose() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = listener.localPort

        assertTrue(isReachableAdbEndpoint("127.0.0.1", port, 500))
        listener.close()

        assertFalse(isReachableAdbEndpoint("127.0.0.1", port, 500))
    }

    @Test
    fun rejectsInvalidEndpointArgumentsWithoutCrashing() {
        assertFalse(isReachableAdbEndpoint("127.0.0.1", -1, 500))
        assertFalse(isReachableAdbEndpoint("127.0.0.1", 5555, -1))
    }
}
