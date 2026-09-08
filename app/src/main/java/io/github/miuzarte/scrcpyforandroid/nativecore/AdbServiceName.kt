package io.github.miuzarte.scrcpyforandroid.nativecore

/** Older Android NSD implementations may wrap a DNS service label in quotes. */
internal fun matchesAdbServiceName(actual: String, expected: String): Boolean =
    actual == expected || actual == "\"$expected\""
