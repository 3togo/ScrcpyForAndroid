plugins { `java-library` }

group = "io.github.miuzarte"
version = "0.5.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
