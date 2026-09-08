// Independent entry point: Linux builds do not require the Android SDK or NDK.
rootProject.name = "scrcaster-desktop"
include(":core")
project(":core").projectDir = file("../core")
