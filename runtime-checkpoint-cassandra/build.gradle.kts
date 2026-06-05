plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    api(project(":runtime"))
    api(platform("org.apache.pekko:pekko-bom_2.13:1.6.0"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-cluster-sharding-typed_2.13")
    implementation("org.apache.pekko:pekko-persistence-typed_2.13")
    implementation("org.apache.pekko:pekko-serialization-jackson_2.13")
    implementation("org.apache.pekko:pekko-persistence-cassandra_2.13:1.1.0")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
