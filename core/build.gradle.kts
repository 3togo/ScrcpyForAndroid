plugins { `java-library` }

group = "io.github.3togo"
version = "0.5.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
