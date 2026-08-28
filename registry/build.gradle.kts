plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // ADR-005: kaml is the chosen YAML technology for the registry.
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
}
