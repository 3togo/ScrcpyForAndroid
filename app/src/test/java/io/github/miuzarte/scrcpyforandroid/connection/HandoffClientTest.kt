package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real phone→TV contract in-process: the production [HandoffClient] posts to a real
 * [HandoffServer], so the payload encoding and the server's parsing are verified together without
 * any device.
 */
class HandoffClientTest {

    @Test fun clientAndServerAgreeOnAFullRoundTrip() = runBlocking {
        val server = HandoffServer.open("127.0.0.1") ?: error("could not bind handoff server")
        server.use {
            val pending = async(Dispatchers.IO) { server.awaitEndpoint() }
            val sent = withContext(Dispatchers.IO) {
                HandoffClient.send(server.target, formatEndpoint("172.16.30.109", 37425))
            }
            assertTrue(sent)
            assertEquals(ConnectionEndpoint("172.16.30.109", 37425), pending.await())
        }
    }

    @Test fun clientSendsTheIpv6FormTheServerParses() = runBlocking {
        val server = HandoffServer.open("127.0.0.1") ?: error("could not bind handoff server")
        server.use {
            val pending = async(Dispatchers.IO) { server.awaitEndpoint() }
            assertTrue(withContext(Dispatchers.IO) { HandoffClient.send(server.target, formatEndpoint("fe80::1", 5555)) })
            assertEquals(ConnectionEndpoint("fe80::1", 5555), pending.await())
        }
    }

    @Test fun clientReportsFailureWhenNothingIsListening() {
        val server = HandoffServer.open("127.0.0.1") ?: error("could not bind handoff server")
        val target = server.target
        server.close()
        assertFalse(HandoffClient.send(target, "172.16.30.109:37425"))
    }
}
