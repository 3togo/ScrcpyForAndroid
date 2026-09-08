package io.github.togo3.scrcaster.services

import android.os.Environment
import java.io.File

object PublicDirs {
    private const val ROOT_DIRECTORY = "ScrCaster"

    fun transferDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ROOT_DIRECTORY,
        )
    }

    fun recordDirectory(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ROOT_DIRECTORY,
        )
    }
}
