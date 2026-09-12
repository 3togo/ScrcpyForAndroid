package io.github.miuzarte.scrcpyforandroid.scan

/**
 * 摄像头扫码得到的二维码内容分类。
 *
 * 有意保持为纯 Kotlin: 不依赖 Android 与相机实现, 便于离线单测覆盖解析规则。
 */
internal sealed class ScannedQr {

    /**
     * adb 无线调试配对二维码 `WIFI:T:ADB;S:<服务名>;P:<密钥>;;`。
     *
     * 系统的扫码界面只是给那些自身不带 adb 客户端的设备准备的; 本应用自带 adb 客户端身份与
     * 密钥 (与手输配对码同一条路径), 因此扫到服务名与密钥后可以直接配对, 无需交给系统。
     */
    data class AdbPairing(val name: String, val secret: String) : ScannedQr()

    /** 明文无线调试地址 `host:port` / `[ipv6]:port`, 可直接用于连接。 */
    data class Address(val host: String, val port: Int) : ScannedQr()

    /** 无法识别的文本, 原样保留给用户判断。 */
    data class Text(val value: String) : ScannedQr()
}

/**
 * 按 adb 的实际产出格式解析: 键值以 `;` 分隔, 结尾多一个空字段 (`...;;`)。
 * 键大小写与字段顺序不做假设, 但 `T` 必须是 `ADB`。
 */
private fun parseAdbPairing(text: String): ScannedQr.AdbPairing? {
    if (!text.startsWith("WIFI:", ignoreCase = true)) return null
    val fields = text
        .removePrefix(text.take(5))
        .split(';')
        .mapNotNull { part ->
            val index = part.indexOf(':')
            if (index <= 0) null else part.substring(0, index).trim().uppercase() to part.substring(index + 1).trim()
        }
        .toMap()

    if (fields["T"]?.equals("ADB", ignoreCase = true) != true) return null
    val name = fields["S"].orEmpty()
    val secret = fields["P"].orEmpty()
    return if (name.isEmpty() || secret.isEmpty()) null else ScannedQr.AdbPairing(name, secret)
}

/** 解析 `host:port`, 支持 IPv6 方括号写法; 端口缺失或越界时返回 null。 */
private fun parseAddress(text: String): ScannedQr.Address? {
    val host: String
    val portText: String

    if (text.startsWith("[")) {
        val close = text.indexOf(']')
        if (close < 0 || !text.startsWith(":", close + 1)) return null
        host = text.substring(1, close)
        portText = text.substring(close + 2)
    } else {
        // 只允许一个分隔冒号, 避免把裸 IPv6 或 URL 误判成地址
        val index = text.indexOf(':')
        if (index <= 0 || text.indexOf(':', index + 1) >= 0) return null
        host = text.substring(0, index)
        portText = text.substring(index + 1)
    }

    val port = portText.toIntOrNull() ?: return null
    if (host.isEmpty() || host.any(Char::isWhitespace)) return null
    if (port !in 1..65535) return null
    return ScannedQr.Address(host, port)
}

/** 分类扫描结果; 顺序即优先级: 配对载荷 > 地址 > 其他文本。 */
internal fun classifyScannedQr(raw: String): ScannedQr {
    val text = raw.trim()
    if (text.isEmpty()) return ScannedQr.Text(text)
    return parseAdbPairing(text) ?: parseAddress(text) ?: ScannedQr.Text(text)
}
