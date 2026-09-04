package io.github.miuzarte.scrcpyforandroid
import org.junit.Assert.*
import org.junit.Test
class TvEndpointTest {
    @Test fun acceptsFullAddresses() {
        assertEquals("192.168.1.20" to 37123, parseTvEndpoint(" 192.168.1.20:37123 "))
        assertEquals("fe80::1" to 5555, parseTvEndpoint("[fe80::1]:5555"))
    }
    @Test fun requiresAHostAndValidExplicitPort() {
        listOf("192.168.1.20", "37123", ":5555", "phone:0", "phone:65536", "phone:no", "a b:5555").forEach {
            assertNull(it, parseTvEndpoint(it))
        }
    }
}
