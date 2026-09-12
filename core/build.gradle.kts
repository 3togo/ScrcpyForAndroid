plugins { `java-library` }

group = "io.github.togo3"
version = "0.5.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    testImplementation(libs.junit)
}
