package io.github.togo3.scrcaster.services

import io.github.togo3.scrcaster.scrcpy.ClientOptions.RecordFormat

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
