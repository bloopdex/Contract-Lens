plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("dev.bloopdex.contractlens.cli.MainKt")
    applicationName = "contractlens"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":openapi-parser"))
    implementation(project(":snapshot-store"))
    implementation(project(":registry"))

    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.slf4j:slf4j-api:2.0.16")
    // logback + the logstash JSON encoder are implementation deps:
    // the CLI sets the log level and emits structured kv events directly.
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("com.github.ajalt.clikt:clikt:5.0.3")
}
