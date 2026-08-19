plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // ADR-005: kaml is the chosen YAML technology for the registry.
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}
