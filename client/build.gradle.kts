plugins {
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":runtime"))

    implementation("dev.langchain4j:langchain4j-core:1.15.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")

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
