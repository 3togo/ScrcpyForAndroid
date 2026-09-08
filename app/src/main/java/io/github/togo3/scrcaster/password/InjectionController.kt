package io.github.togo3.scrcaster.password

import io.github.togo3.scrcaster.services.AppRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InjectionController {
    suspend fun inject(password: CharArray) {
        try {
            withContext(Dispatchers.IO) {
                AppRuntime.scrcpy?.injectText(String(password))
            }
        } finally {
            password.fill('\u0000')
        }
    }
}
