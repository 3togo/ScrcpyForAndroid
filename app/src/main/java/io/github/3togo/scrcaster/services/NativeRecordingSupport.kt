package io.github.3togo.scrcaster.services

import io.github.3togo.scrcaster.scrcpy.ClientOptions.RecordFormat

object NativeRecordingSupport {
    val supportedFormats: List<RecordFormat> = listOf(
        RecordFormat.AUTO,
        RecordFormat.MP4,
        RecordFormat.M4A,
        RecordFormat.AAC,
        RecordFormat.WAV,
    )

    fun isSupported(format: RecordFormat): Boolean {
        return format in supportedFormats
    }
}
