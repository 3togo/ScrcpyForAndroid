package io.github.miuzarte.scrcpyforandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffPayloadTest {

    @Test fun buildsHttpPayloadForIpv4() {
        assertEquals(
            "http://172.16.20.143:41234/abc123",
            HandoffTarget("172.16.20.143", 41234, "abc123").payload,
        )
    }

    @Test fun bracketsIpv6HostInPayload() {
        assertEquals(
            "http://[fe80::1]:41234/token",
            HandoffTarget("fe80::1", 41234, "token").payload,
        )
    }

    @Test fun parsesItsOwnPayload() {
        val target = HandoffTarget("192.168.1.20", 37123, "kT9")
        assertEquals(target, parseHandoffTarget(target.payload))
        assertEquals(target, parseHandoffTarget("  ${target.payload}  "))
        assertEquals(target, parseHandoffTarget("HTTP://192.168.1.20:37123/kT9"))
    }

    @Test fun parsesBracketedIpv6() {
        assertEquals(
            HandoffTarget("fe80::1", 5555, "token"),
            parseHandoffTarget("http://[fe80::1]:5555/token"),
        )
    }

    @Test fun buildsAndParsesTheAppDeepLink() {
        val target = HandoffTarget("172.16.20.143", 41234, "kT9")
        assertEquals("scrcaster://connect?h=172.16.20.143&p=41234&t=kT9", target.appUri)
        assertEquals(target, parseHandoffTarget(target.appUri))
        // Scheme/host casing must not matter.
        assertEquals(target, parseHandoffTarget("SCRCASTER://connect?h=172.16.20.143&p=41234&t=kT9"))
    }

    @Test fun ipv6HostRoundTripsThroughTheAppDeepLink() {
        val target = HandoffTarget("fe80::1", 5555, "tok")
        assertEquals("scrcaster://connect?h=fe80::1&p=5555&t=tok", target.appUri)
        assertEquals(target, parseHandoffTarget(target.appUri))
    }

    @Test fun appDeepLinkToleratesExtraParametersAndOrder() {
        assertEquals(
            HandoffTarget("10.0.0.2", 9999, "tok"),
            parseHandoffTarget("scrcaster://connect?t=tok&p=9999&h=10.0.0.2&x=1"),
        )
    }

    @Test fun tokenAndHostCharsetRoundTrip() {
        val target = HandoffTarget("tv-1.local", 65535, "ab-cD_1.2")
        assertEquals(target, parseHandoffTarget(target.appUri))
        assertEquals(target, parseHandoffTarget(target.payload))
    }

    @Test fun ignoresTrailingPathQueryAndFragment() {
        assertEquals(
            HandoffTarget("10.0.0.2", 9999, "token"),
            parseHandoffTarget("http://10.0.0.2:9999/token/extra?x=1#frag"),
        )
    }

    @Test fun rejectsMalformedAppDeepLinks() {
        assertNull(parseHandoffTarget("scrcaster://connect"))
        assertNull(parseHandoffTarget("scrcaster://connect?h=&p=5555&t=tok"))
        assertNull(parseHandoffTarget("scrcaster://connect?p=5555&t=tok"))
        assertNull(parseHandoffTarget("scrcaster://connect?h=1.2.3.4&p=5555"))
        assertNull(parseHandoffTarget("scrcaster://other?h=1.2.3.4&p=5555&t=tok"))
        assertNull(parseHandoffTarget("scrcaster://connect?h=1.2.3.4&p=70000&t=tok"))
        assertNull(parseHandoffTarget("scrcaster://connect?h=1.2.3.4&p=0&t=tok"))
    }

    @Test fun rejectsAnythingElse() {
        // Plain address, pairing payload and unrelated URLs must not be mistaken for a handoff.
        assertNull(parseHandoffTarget("192.168.1.5:5555"))
        assertNull(parseHandoffTarget("WIFI:T:ADB;S:studio-x;P:secret;;"))
        assertNull(parseHandoffTarget("http://1.2.3.4:5555"))
        assertNull(parseHandoffTarget("http://1.2.3.4:5555/"))
        assertNull(parseHandoffTarget("http://1.2.3.4:0/tok"))
        assertNull(parseHandoffTarget("http://1.2.3.4:70000/tok"))
        assertNull(parseHandoffTarget("https://1.2.3.4:5555/tok"))
        assertNull(parseHandoffTarget("http://[fe80::1]/tok"))
        assertNull(parseHandoffTarget(""))
    }
}
