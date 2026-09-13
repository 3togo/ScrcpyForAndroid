package io.github.miuzarte.scrcpyforandroid.nativecore

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val CONNECT_PROBE_TIMEOUT_MS = 750

/**
 * Performs mDNS discovery for ADB TLS pairing/connect services on the local network.
 *
 * Uses Android's `NsdManager` to resolve services and returns a host:port pair
 * when a suitable service is found within the provided timeout.
 */
internal object AdbMdnsDiscoverer {

    private lateinit var nsdManager: NsdManager

    fun init(context: Context) {
        if (::nsdManager.isInitialized) return
        nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    }

    /**
     * Discover a device that advertises the ADB connect service via mDNS.
     */
    fun discoverConnectService(timeoutMs: Long, includeLanDevices: Boolean): Pair<String, Int>? {
        return discoverService(TLS_CONNECT, timeoutMs, includeLanDevices)
    }

    /**
     * Discover a device that advertises the ADB pairing service via mDNS.
     */
    fun discoverPairingService(timeoutMs: Long, includeLanDevices: Boolean): Pair<String, Int>? {
        return discoverService(TLS_PAIRING, timeoutMs, includeLanDevices)
    }

    fun discoverQrService(name: String, timeoutMs: Long): Pair<String, Int>? =
        discoverService(TLS_PAIRING, timeoutMs, true, expectedName = name)

    /**
     * Finds a live TLS connection port, not merely the first matching mDNS record. Android may
     * briefly advertise both its current port and a stale, already-closed port after adbd rotates
     * ports. Returning the stale record made TV QR pairing fail while entering the current address
     * manually worked. The reachability probe only opens and closes TCP; the subsequent ADB TLS
     * handshake still authenticates the endpoint before it can be used.
     */
    fun discoverConnectForHost(host: String, timeoutMs: Long): Pair<String, Int>? =
        discoverService(TLS_CONNECT, timeoutMs, true, expectedHost = host, requireReachable = true)

    private fun discoverService(
        serviceType: String,
        timeoutMs: Long,
        includeLanDevices: Boolean,
        expectedName: String? = null,
        expectedHost: String? = null,
        requireReachable: Boolean = false,
    ): Pair<String, Int>? {
        check(::nsdManager.isInitialized) { "AdbMdnsDiscoverer is not initialized" }
        if (timeoutMs <= 0) return null
        val resultPort = AtomicInteger(-1)
        val resultHost = AtomicReference<String?>(null)
        val discoveryFinished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val probeExecutor: ExecutorService? = if (requireReachable) {
            Executors.newFixedThreadPool(MAX_CONCURRENT_PROBES) { task ->
                Thread(task, "adb-mdns-port-probe").apply { isDaemon = true }
            }
        } else {
            null
        }

        fun accept(hostAddress: String, port: Int) {
            if (resultPort.compareAndSet(-1, port)) {
                resultHost.set(hostAddress)
                discoveryFinished.set(true)
                latch.countDown()
            }
        }

        val discoveryListener = object: NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.v(TAG, "discovery started: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $serviceType, error=$errorCode")
                latch.countDown()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.v(TAG, "discovery stopped: $serviceType")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed: $serviceType, error=$errorCode")
            }

            @Suppress("DEPRECATION")
            @SuppressLint("NewApi")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (discoveryFinished.get()) return
                Log.v(TAG, "service found: ${serviceInfo.serviceName}")
                if (expectedName != null && !matchesAdbServiceName(serviceInfo.serviceName, expectedName)) return
                val resolveListener = object: NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.v(TAG, "resolve failed: ${serviceInfo.serviceName}, error=$errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (discoveryFinished.get()) return
                        val hostAddress = resolvedHostAddress(serviceInfo) ?: return
                        if (hostAddress.isBlank()) return
                        if (expectedHost != null && hostAddress != expectedHost) return

                        if (!includeLanDevices) {
                            val isLocalHost = runCatching {
                                NetworkInterface.getNetworkInterfaces().asSequence().any { intf ->
                                    intf.inetAddresses.asSequence().any { addr ->
                                        addr.hostAddress == hostAddress
                                    }
                                }
                            }.getOrDefault(false)
                            if (!isLocalHost) return
                            if (!isPortOpened(serviceInfo.port)) return
                        }

                        if (requireReachable) {
                            // Do not block NsdManager's callback thread while a stale port times out.
                            try {
                                probeExecutor?.execute {
                                    if (!discoveryFinished.get() &&
                                        isReachableAdbEndpoint(hostAddress, serviceInfo.port)
                                    ) {
                                        accept(hostAddress, serviceInfo.port)
                                    }
                                }
                            } catch (_: RejectedExecutionException) {
                                // Discovery timed out while this resolve callback was in flight.
                            }
                        } else {
                            accept(hostAddress, serviceInfo.port)
                        }
                    }
                }
                runCatching {
                    nsdManager.resolveService(serviceInfo, resolveListener)
                }.onFailure { e ->
                    Log.w(TAG, "resolveService failed for ${serviceInfo.serviceName}", e)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.v(TAG, "service lost: ${serviceInfo.serviceName}")
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: RuntimeException) {
            Log.w(TAG, "discoverServices failed for $serviceType", error)
        } finally {
            discoveryFinished.set(true)
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            probeExecutor?.shutdownNow()
        }

        val port = resultPort.get()
        val host = resultHost.get()
        return if (port > 0 && !host.isNullOrBlank()) host to port else null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun resolvedHostAddress(serviceInfo: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            serviceInfo.hostAddresses.firstOrNull()?.hostAddress
        else
            serviceInfo.host?.hostAddress

    private fun isPortOpened(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: IOException) {
        true
    }

    private const val TAG = "AdbMdnsDiscoverer"
    private const val MAX_CONCURRENT_PROBES = 2
    private const val TLS_CONNECT = "_adb-tls-connect._tcp"
    private const val TLS_PAIRING = "_adb-tls-pairing._tcp"
}

/** TCP preflight used to discard stale mDNS records before the authenticated ADB connection. */
internal fun isReachableAdbEndpoint(
    host: String,
    port: Int,
    timeoutMs: Int = CONNECT_PROBE_TIMEOUT_MS,
): Boolean = try {
    Socket().use {
        it.connect(InetSocketAddress(host, port), timeoutMs)
        true
    }
} catch (_: Exception) {
    false
}
