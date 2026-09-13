package io.github.miuzarte.scrcpyforandroid.connection

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phone → TV push of this phone's Wireless debugging `host:port`, used by the receiver handoff
 * flow. Plain unicast TCP, so unlike mDNS it traverses routers between subnets.
 */
internal object HandoffClient {

    private const val TAG = "HandoffClient"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** Posts [address] (`host:port`) to the receiver described by [target]; true on 2xx. */
    fun send(target: HandoffTarget, address: String): Boolean {
        Log.i(TAG, "send(): POST ${target.payload} address=$address")
        return runCatching {
            val connection = (URL(target.payload).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                connection.outputStream.use { stream ->
                    stream.write("address=${URLEncoder.encode(address, "UTF-8")}".toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                Log.i(TAG, "send(): HTTP $code")
                code in 200..299
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "send(): failed", it) }.getOrDefault(false)
    }
}
