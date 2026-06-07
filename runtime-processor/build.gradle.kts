plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":runtime"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
