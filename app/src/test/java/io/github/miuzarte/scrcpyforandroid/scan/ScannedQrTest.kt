package io.github.miuzarte.scrcpyforandroid.scan

import io.github.miuzarte.scrcpyforandroid.connection.HandoffTarget
import io.github.miuzarte.scrcpyforandroid.connection.buildAdbQrPairing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扫码结果的分类规则。摄像头与解析分离, 因此这部分可以完全离线测试。
 */
class ScannedQrTest {

    @Test fun ourOwnGeneratedPayloadRoundTripsThroughTheScanner() {
        val pairing = buildAdbQrPairing()

        assertEquals(
            ScannedQr.AdbPairing(pairing.name, pairing.secret),
            classifyScannedQr(pairing.payload),
        )
    }

    @Test fun pairingPayloadToleratesFieldOrderAndLetterCase() {
        assertEquals(
            ScannedQr.AdbPairing("studio-0123abcd", "deadbeef"),
            classifyScannedQr("wifi:P:deadbeef;T:adb;S:studio-0123abcd;;"),
        )
    }

    @Test fun nonAdbWifiPayloadStaysOpaqueText() {
        //  Wi-Fi 凭据等其它 WIFI: 载荷不属于本功能, 不当成配对码也不当成地址
        assertEquals(
            ScannedQr.Text("WIFI:T:WPA;S:lab;P:secret;;"),
            classifyScannedQr("WIFI:T:WPA;S:lab;P:secret;;"),
        )
    }

    @Test fun pairingPayloadWithoutSecretIsNotTreatedAsPairing() {
        assertEquals(
            ScannedQr.Text("WIFI:T:ADB;S:studio-0123abcd;;"),
            classifyScannedQr("WIFI:T:ADB;S:studio-0123abcd;;"),
        )
    }

    @Test fun pairingServiceNameIsNotRestrictedToASingleToken() {
        // 扫到的配对码现在直接送去配对, 因此服务名的各种写法都得认得
        assertEquals(
            ScannedQr.AdbPairing("Pixel 8 Pro", "abcdef"),
            classifyScannedQr("WIFI:T:ADB;S:Pixel 8 Pro;P:abcdef;;"),
        )
        assertEquals(
            ScannedQr.AdbPairing("adb-10AE5M29NL00174-qk4LmF", "123456"),
            classifyScannedQr("WIFI:T:ADB;S:adb-10AE5M29NL00174-qk4LmF;P:123456;;"),
        )
    }

    @Test fun ipv4AddressBecomesConnectableTarget() {
        assertEquals(
            ScannedQr.Address("172.16.30.109", 39831),
            classifyScannedQr(" 172.16.30.109:39831 "),
        )
    }

    @Test fun bracketedIpv6AddressKeepsItsOwnColons() {
        assertEquals(
            ScannedQr.Address("fe80::1", 5555),
            classifyScannedQr("[fe80::1]:5555"),
        )
    }

    @Test fun bareIpv6WithoutBracketsIsNotGuessedAsAnAddress() {
        assertEquals(
            ScannedQr.Text("fe80::1"),
            classifyScannedQr("fe80::1"),
        )
    }

    @Test fun hostWithoutPortIsNotAnAddress() {
        assertEquals(ScannedQr.Text("tv.local"), classifyScannedQr("tv.local"))
    }

    @Test fun portOutsideTheValidRangeIsRejected() {
        assertEquals(ScannedQr.Text("1.2.3.4:0"), classifyScannedQr("1.2.3.4:0"))
        assertEquals(ScannedQr.Text("1.2.3.4:70000"), classifyScannedQr("1.2.3.4:70000"))
        assertEquals(ScannedQr.Text("[fe80::1]:0"), classifyScannedQr("[fe80::1]:0"))
    }

    @Test fun urlsAndEmptyTextStayText() {
        assertEquals(
            ScannedQr.Text("http://1.2.3.4:5555"),
            classifyScannedQr("http://1.2.3.4:5555"),
        )
        assertEquals(ScannedQr.Text(""), classifyScannedQr("   "))
    }

    @Test fun receiverHandoffUrlIsRecognized() {
        val target = HandoffTarget("172.16.20.143", 41234, "tok")
        val handoff = ScannedQr.Handoff(target)
        // TV 的"扫码接收地址"载荷, 必须优先于普通地址/文本被识别
        assertEquals(handoff, classifyScannedQr("http://172.16.20.143:41234/tok"))
        // 自定义 scheme 深链: 可由交接网页自动跳转, 或系统相机直接打开
        assertEquals(handoff, classifyScannedQr("scrcaster://connect?h=172.16.20.143&p=41234&t=tok"))
    }
}
