package io.github.miuzarte.scrcpyforandroid.nativecore

import android.media.MediaCodec

/**
 * Creates, configures, and starts a codec without leaking the native codec when either of the
 * latter operations fails. Callers only receive a fully started codec.
 */
internal inline fun createStartedMediaCodec(
    create: () -> MediaCodec,
    configure: MediaCodec.() -> Unit,
): MediaCodec {
    val codec = create()
    try {
        codec.configure()
        codec.start()
        return codec
    } catch (error: Throwable) {
        runCatching { codec.release() }
        throw error
    }
}
