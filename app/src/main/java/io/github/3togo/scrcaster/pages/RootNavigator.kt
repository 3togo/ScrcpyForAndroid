package io.github.3togo.scrcaster.pages

import androidx.compose.runtime.staticCompositionLocalOf

class RootNavigator(
    val push: (RootScreen) -> Unit,
    val pop: () -> Unit,
)

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator> {
    error("No RootNavigator provided")
}
