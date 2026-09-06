plugins { application }

repositories { mavenCentral() }
dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jmdns:jmdns:3.6.3")
}

group = "io.github.miuzarte"
version = "0.5.5"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
application { mainClass.set("io.github.miuzarte.scrcpy.desktop.DesktopApp") }

val backendTest = tasks.register<JavaExec>("backendTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.miuzarte.scrcpy.desktop.BackendTest")
    jvmArgs("-ea")
}
tasks.check { dependsOn(backendTest) }
// Tests have a standalone main entry point, with no JUnit dependency.
tasks.test { enabled = false; dependsOn(backendTest) }

tasks.register<JavaExec>("guiSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.miuzarte.scrcpy.desktop.GuiSmokeTest")
    jvmArgs("-ea", "-Djava.util.prefs.userRoot=${layout.buildDirectory.get()}/test-preferences")
    environment("ADB", "/usr/bin/true")
    environment("SCRCPY", "/usr/bin/true")
}
