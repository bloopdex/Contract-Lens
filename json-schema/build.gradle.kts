plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // JSON parsing reuses kotlinx-serialization (already a core dep);
    // no new parsing framework is needed for the JSON Schema adapter.
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
}
