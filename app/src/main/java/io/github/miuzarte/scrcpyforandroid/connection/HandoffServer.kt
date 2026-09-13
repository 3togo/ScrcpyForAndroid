package io.github.miuzarte.scrcpyforandroid.connection

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.coroutines.resume

private const val TAG = "HandoffServer"
private const val REQUEST_TIMEOUT_MS = 10_000
private const val MAX_LINE_CHARS = 8 * 1024
private const val TOKEN_LENGTH = 12
private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private val handoffRandom = SecureRandom()

/**
 * One-shot unicast receiver server.
 *
 * Publishes [payload] (a QR the phone reads) and waits for the phone to POST its Wireless
 * debugging `host:port`. Only the `POST /<token>` request ends the wait; other requests serve the
 * small fallback form so a phone *without* ScrCaster can still type the address in a browser.
 *
 * Pure `java.net`, no Android APIs, so it can be exercised from JVM unit tests.
 */
internal class HandoffServer private constructor(
    private val socket: ServerSocket,
    val target: HandoffTarget,
) : AutoCloseable {

    val payload: String = target.payload

    /**
     * Blocks until a valid address arrives. Cancelling the calling coroutine closes the socket and
     * returns null; a closed socket also makes [ServerSocket.accept] throw, which ends the worker.
     */
    suspend fun awaitEndpoint(): ConnectionEndpoint? = suspendCancellableCoroutine { continuation ->
        val worker = Thread({
            val endpoint = runCatching { acceptLoop() }.getOrNull()
            Log.i(TAG, "awaitEndpoint(): result=$endpoint")
            if (continuation.isActive) continuation.resume(endpoint)
        }, "handoff-server")
        worker.isDaemon = true
        worker.start()
        continuation.invokeOnCancellation { runCatching { close() } }
    }

    private fun acceptLoop(): ConnectionEndpoint? {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return null
            val endpoint = runCatching { client.use(::serve) }.getOrNull()
            if (endpoint != null) return endpoint
        }
        return null
    }

    private fun serve(client: Socket): ConnectionEndpoint? {
        client.soTimeout = REQUEST_TIMEOUT_MS
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)?.uppercase().orEmpty()
        val path = parts.getOrNull(1).orEmpty()

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }

        Log.i(TAG, "request: $method $path from ${client.inetAddress?.hostAddress}")
        val token = path.trimStart('/').substringBefore('?')
        if (token != target.token) {
            Log.w(TAG, "rejected: bad token '$token'")
            respond(client, 404, "Not found")
            return null
        }
        when (method) {
            "GET", "HEAD" -> respond(client, 200, page(), "text/html; charset=utf-8")
            "POST" -> {
                val address = formValue(readBody(input, contentLength), "address")
                val endpoint = address?.let(ConnectionEndpoint::parse)
                if (endpoint == null) {
                    Log.w(TAG, "POST had no valid address: '$address'")
                    respond(client, 400, "Enter a full address like 192.168.1.20:37123")
                } else {
                    Log.i(TAG, "accepted address=$endpoint")
                    respond(client, 200, "OK")
                    return endpoint
                }
            }
            else -> respond(client, 405, "Method not allowed")
        }
        return null
    }

    private fun page(): String = """
        <!doctype html><html lang="en"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>ScrCaster receiver</title></head>
        <body style="font-family:system-ui;margin:24px;max-width:32rem">
        <h2>Connect to the receiver</h2>
        <p>With ScrCaster installed this page opens it automatically. Otherwise enter this phone's
        <b>Wireless debugging</b> “IP address &amp; port” and send it to the TV.</p>
        <p><a href="${target.appUri}"
              style="display:inline-block;font-size:18px;padding:10px 16px;background:#6750a4;color:#fff;border-radius:8px;text-decoration:none">
           Open ScrCaster</a></p>
        <form method="post" action="/${target.token}">
          <input name="address" placeholder="192.168.1.20:37123" autocomplete="off"
                 style="font-size:18px;padding:8px;width:100%;box-sizing:border-box">
          <button type="submit" style="font-size:18px;padding:8px;margin-top:12px">Send to TV</button>
        </form>
        <script>
          // Hand off to the app so an in-app scan is unnecessary; harmless when it is not installed.
          try { window.location.href = "${target.appUri}"; } catch (e) {}
        </script>
        </body></html>
    """.trimIndent()

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** Binds an ephemeral port on all interfaces; null when no LAN IPv4 address is available. */
        fun open(): HandoffServer? = open(lanHostAddress())

        /** Test seam: binds the same way but lets a unit test pin the advertised host. */
        internal fun open(host: String?): HandoffServer? {
            val resolved = host
            if (resolved == null) { Log.w(TAG, "open(): no LAN IPv4 address"); return null }
            val socket = runCatching { ServerSocket(0) }.getOrNull()
            if (socket == null) { Log.w(TAG, "open(): bind failed"); return null }
            return HandoffServer(socket, HandoffTarget(resolved, socket.localPort, randomToken()))
                .also { Log.i(TAG, "open(): serving ${it.payload}") }
        }

        private fun lanHostAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        private fun randomToken(): String = buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[handoffRandom.nextInt(TOKEN_ALPHABET.length)]) }
        }
    }
}

/** Reads one request line, dropping the trailing CR so header parsing sees plain text. */
private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    while (true) {
        val byte = input.read()
        if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
        if (byte == '\n'.code) break
        if (byte != '\r'.code) buffer.write(byte)
        if (buffer.size() > MAX_LINE_CHARS) return null
    }
    return buffer.toString("ISO-8859-1")
}

private fun readBody(input: InputStream, length: Int): String {
    if (length <= 0) return ""
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = input.read(bytes, read, length - read)
        if (count < 0) break
        read += count
    }
    return String(bytes, 0, read, Charsets.UTF_8)
}

private fun formValue(body: String, key: String): String? =
    body.split('&').firstNotNullOfOrNull { field ->
        val equals = field.indexOf('=')
        if (equals <= 0 || field.substring(0, equals) != key) null
        else runCatching { URLDecoder.decode(field.substring(equals + 1), "UTF-8") }.getOrNull()
    }

private fun respond(client: Socket, code: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val reason = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }
    val head = "HTTP/1.1 $code $reason\r\n" +
        "Content-Type: $contentType\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Connection: close\r\n\r\n"
    client.getOutputStream().use {
        it.write(head.toByteArray(Charsets.US_ASCII))
        it.write(bytes)
        it.flush()
    }
}
