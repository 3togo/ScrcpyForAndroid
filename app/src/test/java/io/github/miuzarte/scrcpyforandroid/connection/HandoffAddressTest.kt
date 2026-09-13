package io.github.miuzarte.scrcpyforandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffAddressTest {

    @Test fun sameSubnetWinsOverInterfaceOrder() {
        val candidates = listOf(
            LocalIpv4("10.1.2.3", 8),
            LocalIpv4("172.16.21.9", 23),
        )
        assertEquals("172.16.21.9", pickAdvertisedIpv4(candidates, "172.16.20.143"))
    }

    @Test fun prefixLengthDecidesTheSubnet() {
        // A /24 does not cover the receiver's .21 host, so the first site-local address is used.
        val candidates = listOf(
            LocalIpv4("10.1.2.3", 8),
            LocalIpv4("172.16.21.9", 24),
        )
        assertEquals("10.1.2.3", pickAdvertisedIpv4(candidates, "172.16.20.143"))
    }

    @Test fun aSlashTwentyThreeSpansAdjacentThirdOctets() {
        assertEquals(
            "172.16.21.9",
            pickAdvertisedIpv4(listOf(LocalIpv4("172.16.21.9", 23)), "172.16.20.143"),
        )
    }

    @Test fun fallsBackToSiteLocalWhenTheReceiverIsNotIpv4() {
        val candidates = listOf(
            LocalIpv4("8.8.4.4", 32),
            LocalIpv4("192.168.1.7", 24),
        )
        assertEquals("192.168.1.7", pickAdvertisedIpv4(candidates, "tv.local"))
    }

    @Test fun usesTheFirstCandidateWhenNoneIsSiteLocal() {
        assertEquals("8.8.4.4", pickAdvertisedIpv4(listOf(LocalIpv4("8.8.4.4", 32)), "1.1.1.1"))
    }

    @Test fun returnsNullWithoutCandidates() {
        assertNull(pickAdvertisedIpv4(emptyList(), "172.16.20.143"))
    }

    @Test fun formatsIpv4AndBracketsIpv6() {
        assertEquals("172.16.30.109:5555", formatEndpoint("172.16.30.109", 5555))
        // Without brackets the receiver parsed `fe80::1:5555` as no address at all.
        assertEquals("[fe80::1]:5555", formatEndpoint("fe80::1", 5555))
    }

    @Test fun formattedAddressIsAcceptedByTheReceiverParser() {
        assertEquals(
            ConnectionEndpoint("172.16.30.109", 5555),
            ConnectionEndpoint.parse(formatEndpoint("172.16.30.109", 5555)),
        )
        // Bracketing is what makes an IPv6 host acceptable to the receiver.
        assertEquals(
            ConnectionEndpoint("fe80::1", 5555),
            ConnectionEndpoint.parse(formatEndpoint("fe80::1", 5555)),
        )
    }

    @Test fun anUnbracketedIpv6HostIsNotAnAcceptableAddress() {
        assertNull(ConnectionEndpoint.parse("fe80::1:5555"))
    }
}
