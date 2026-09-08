package io.github.3togo.scrcaster.password

object PasswordSanitizer {
    fun filterName(input: String): String {
        return input
            .filterNot(Char::isISOControl)
            .replace('\u0000', ' ')
            .trim()
    }

    fun filterPassword(input: String): String {
        return input.filter { ch ->
            !ch.isISOControl() && ch != '\u0000'
        }
    }
}
