package io.github.togo3.scrcaster.password

enum class PasswordCreatedState {
    AuthenticatedCreated,
    UnauthenticatedCreated,
    AuthenticatedCreatedModified,
}

val PasswordCreatedState.hasAuthenticatedOrigin: Boolean
    get() = this != PasswordCreatedState.UnauthenticatedCreated

data class PasswordEntry(
    val id: String,
    val name: String,
    val cipherText: CharArray?,
    val createdWithAuth: PasswordCreatedState,
)
