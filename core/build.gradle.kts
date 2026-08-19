plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
}

// Phase 5 fuzz suite: the classifier/diff invariants at scale.
tasks.register<Test>("fuzz") {
    description = "Runs the classifier fuzz suite with the configured iteration count"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("fuzz.iterations", project.findProperty("fuzzIterations")?.toString() ?: "2000")
    filter { includeTestsMatching("*FuzzTest") }
    testLogging { events("failed") }
}
