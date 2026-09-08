package io.github.togo3.scrcaster.password

import androidx.fragment.app.FragmentActivity
import io.github.togo3.scrcaster.R
import io.github.togo3.scrcaster.services.AppRuntime

class PasswordUseCase {
    suspend fun preparePassword(
        activity: FragmentActivity,
        entry: PasswordEntry,
        globalRequiresAuth: Boolean,
        authTitle: String,
    ): Result<CharArray> {
        val canAuthNow = BiometricGate.canAuthenticate()

        if (globalRequiresAuth) {
            if (
                !BiometricGate.authenticate(
                    activity = activity,
                    title = authTitle,
                    subtitle = entry.name,
                )
            ) return Result.failure(IllegalStateException(AppRuntime.stringResource(R.string.password_auth_failed)))
        } else if (entry.createdWithAuth.hasAuthenticatedOrigin && !canAuthNow) {
            return Result.failure(IllegalStateException(AppRuntime.stringResource(R.string.password_security_state_changed)))
        }

        val password = entry.cipherText
            ?: return Result.failure(
                IllegalStateException(AppRuntime.stringResource(R.string.password_expired)),
            )
        return Result.success(password.copyOf())
    }
}
