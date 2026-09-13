package io.github.miuzarte.scrcpyforandroid.connection

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.nativecore.AdbMdnsDiscoverer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

internal enum class HandoffOutcome { SENT, NO_PORT, UNREACHABLE }

/**
 * Phone side of the receiver handoff, shared by the in-app scanner and the deep-link activity:
 * read this phone's Wireless-debugging port (local-only mDNS, so no cross-subnet issue) and push a
 * routable `ip:port` to the TV's one-shot server over plain unicast TCP.
 */
internal object ReceiverHandoff {

    private const val TAG = "ReceiverHandoff"
    private const val DISCOVERY_TIMEOUT_MS = 8_000L

    suspend fun send(target: HandoffTarget): HandoffOutcome {
        Log.i(TAG, "send(): target=${target.payload}")
        val discovered = runInterruptible(Dispatchers.IO) {
            // Only accept a service on one of this phone's own interfaces, else we might grab
            // another device's connect port.
            AdbMdnsDiscoverer.discoverConnectService(DISCOVERY_TIMEOUT_MS, includeLanDevices = false)
        }
        if (discovered == null) {
            Log.w(TAG, "send(): local connect service not found")
            return HandoffOutcome.NO_PORT
        }

        // Advertise this phone's own IPv4 rather than the resolved mDNS host: the service often
        // resolves to IPv6, and `fe80::1:5555` is not a valid address for the receiver.
        val host = pickAdvertisedIpv4(localIpv4Addresses(), target.host) ?: discovered.first
        val address = formatEndpoint(host, discovered.second)
        Log.i(TAG, "send(): advertising $address (discovered ${discovered.first}:${discovered.second})")

        val sent = withContext(Dispatchers.IO) { HandoffClient.send(target, address) }
        Log.i(TAG, "send(): result=$sent")
        return if (sent) HandoffOutcome.SENT else HandoffOutcome.UNREACHABLE
    }

    /** Site-local IPv4 addresses of up, non-loopback interfaces, in interface order. */
    private fun localIpv4Addresses(): List<LocalIpv4> = runCatching {
        (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .mapNotNull { interfaceAddress ->
                val text = (interfaceAddress.address as? Inet4Address)?.hostAddress
                    ?: return@mapNotNull null
                LocalIpv4(text, interfaceAddress.networkPrefixLength.toInt())
            }
            .toList()
    }.getOrDefault(emptyList())
}
