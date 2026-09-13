package io.github.miuzarte.scrcpyforandroid.connection

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Test

class HandoffServerTest {

    @Test fun servesFormThenAcceptsAPostedAddress() = runBlocking {
        withServer { server, pending ->
            val url = URL(server.payload)
            assertEquals(200, request(url, "GET"))
            assertEquals(200, request(url, "POST", "address=192.168.1.20%3A37123"))
            assertEquals(ConnectionEndpoint("192.168.1.20", 37123), pending.await())
        }
    }

    @Test fun servedPageHandsOffToTheAppDeepLink() = runBlocking {
        withServer { server, pending ->
            val page = body(URL(server.payload))
            // The fallback page must carry the deep link so scanning with the system camera still
            // lands in ScrCaster instead of dead-ending on a web form.
            assertTrue("page must embed the app deep link", page.contains(server.target.appUri))
            assertTrue(page.contains("scrcaster://"))

            request(URL(server.payload), "POST", "address=1.2.3.4%3A5555")
            assertEquals(ConnectionEndpoint("1.2.3.4", 5555), pending.await())
        }
    }

    @Test fun acceptsABracketedIpv6Address() = runBlocking {
        withServer { server, pending ->
            assertEquals(200, request(URL(server.payload), "POST", "address=%5Bfe80%3A%3A1%5D%3A5555"))
            assertEquals(ConnectionEndpoint("fe80::1", 5555), pending.await())
        }
    }

    @Test fun rejectsAnUnbracketedIpv6AddressButKeepsWaiting() = runBlocking {
        withServer { server, pending ->
            // `fe80::1:5555` is what the phone used to send; the receiver must not accept it.
            assertEquals(400, request(URL(server.payload), "POST", "address=fe80%3A%3A1%3A5555"))
            assertEquals(200, request(URL(server.payload), "POST", "address=10.0.0.9%3A5555"))
            assertEquals(ConnectionEndpoint("10.0.0.9", 5555), pending.await())
        }
    }

    @Test fun rejectsRequestsWithoutTheAddressField() = runBlocking {
        withServer { server, pending ->
            assertEquals(400, request(URL(server.payload), "POST", "host=1.2.3.4%3A5555"))
            server.close()
            assertNull(pending.await())
        }
    }

    @Test fun rejectsMethodsOtherThanGetAndPost() = runBlocking {
        withServer { server, pending ->
            assertEquals(405, request(URL(server.payload), "PUT", "address=1.2.3.4%3A5555"))
            request(URL(server.payload), "POST", "address=1.2.3.4%3A5555")
            assertEquals(ConnectionEndpoint("1.2.3.4", 5555), pending.await())
        }
    }

    @Test fun rejectsUnknownTokens() = runBlocking {
        withServer { server, pending ->
            assertEquals(404, request(URL("http://127.0.0.1:${server.target.port}/wrong-token"), "GET"))
            server.close()
            assertNull(pending.await())
        }
    }

    @Test fun acceptsTheTokenWithATrailingQuery() = runBlocking {
        withServer { server, pending ->
            assertEquals(200, request(URL("${server.payload}?x=1"), "GET"))
            request(URL(server.payload), "POST", "address=1.2.3.4%3A5555")
            assertEquals(ConnectionEndpoint("1.2.3.4", 5555), pending.await())
        }
    }

    @Test fun closingTheServerEndsTheWaitWithNull() = runBlocking {
        val server = newServer()
        val pending = async(Dispatchers.IO) { server.awaitEndpoint() }
        delay(100)
        server.close()
        assertNull(withTimeout(2_000) { pending.await() })
    }

    @Test fun cancellingTheWaitCancelsTheCoroutine() = runBlocking {
        val server = newServer()
        val job = launch(Dispatchers.IO) { server.awaitEndpoint() }
        delay(100)
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        server.close()
    }

    @Test fun openFailsWithoutAHost() {
        assertNull(HandoffServer.open(null))
    }

    private suspend fun withServer(
        block: suspend (HandoffServer, Deferred<ConnectionEndpoint?>) -> Unit,
    ) = coroutineScope {
        val server = newServer()
        server.use {
            val pending = async(Dispatchers.IO) { server.awaitEndpoint() }
            try {
                block(server, pending)
            } finally {
                server.close()
                pending.cancelAndJoin()
            }
        }
    }

    private fun newServer(): HandoffServer =
        HandoffServer.open("127.0.0.1") ?: error("could not bind handoff server")

    private fun request(url: URL, method: String, body: String? = null): Int {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Connection", "close")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            streamFor(connection, code)?.use { it.readBytes() }
            code
        } finally {
            connection.disconnect()
        }
    }

    private fun body(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Connection", "close")
        }
        return try {
            val code = connection.responseCode
            assertEquals(200, code)
            streamFor(connection, code)?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /** Error responses expose their body on [HttpURLConnection.getErrorStream], not getInputStream. */
    private fun streamFor(connection: HttpURLConnection, code: Int) =
        if (code in 200..299) connection.inputStream else connection.errorStream
}
