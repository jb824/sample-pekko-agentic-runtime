plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":runtime"))
    implementation(platform("org.apache.pekko:pekko-bom_2.13:1.6.0"))
    implementation("org.apache.pekko:pekko-actor-typed_2.13")
    implementation("org.apache.pekko:pekko-stream_2.13")
    implementation("org.apache.pekko:pekko-http_2.13:1.3.0")
    implementation("dev.langchain4j:langchain4j-ollama:1.15.0")
    implementation("com.google.protobuf:protobuf-java:4.32.1")
    implementation("io.grpc:grpc-services:1.76.0")
    implementation("io.opentelemetry:opentelemetry-api:1.45.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.2")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.example.agent.Main"
}

tasks.register<JavaExec>("runRagService") {
    group = "application"
    description = "Run decoupled RAG retrieval/ingestion service."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.agent.rag.service.RagRetrievalServiceMain")
}

tasks.register<JavaExec>("runRagEval") {
    group = "verification"
    description = "Run RAG retrieval evals."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.agent.rag.eval.RagEvalRunner")
}
