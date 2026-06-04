plugins {
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":runtime"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

application {
    mainClass = "com.example.agent.Main"
}
