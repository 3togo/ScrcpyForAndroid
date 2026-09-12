// Independent entry point: Linux builds do not require the Android SDK or NDK.
rootProject.name = "scrcaster-desktop"
include(":core")
project(":core").projectDir = file("../core")

// ":core" uses the shared `libs` version catalog (see gradle/libs.versions.toml)
// and resolves its test dependencies, so the standalone desktop build must expose
// both the catalog and a repository for it.
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
