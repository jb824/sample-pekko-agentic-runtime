apply(plugin = "java-library")

repositories {
    mavenCentral()
}

dependencies {
    "api"(project(":runtime"))
    "annotationProcessor"(project(":runtime"))
    "annotationProcessor"(project(":runtime-processor"))
}

extensions.configure<JavaPluginExtension>("java") {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
