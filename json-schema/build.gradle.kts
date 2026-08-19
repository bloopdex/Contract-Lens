plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // JSON parsing reuses kotlinx-serialization (already a core dep);
    // no new parsing framework is needed for the JSON Schema adapter.
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}
