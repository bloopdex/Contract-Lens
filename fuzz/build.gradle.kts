// Phase 6 (workstream B): coverage-guided fuzz targets via Jazzer
// (jazzer-junit 0.23.0, @FuzzTest). This module is test-only: it
// depends on the production modules and exposes NO main sources.
//
// One task, two modes (Jazzer's own protocol):
//   - regression (DEFAULT, part of every build): replays every
//     committed crashing input found in
//     src/test/resources/...Inputs — the PR gate, seconds.
//   - fuzzing: `-Pjazzer.fuzz=1` sets JAZZER_FUZZ=1 and runs the
//     per-target maxDuration bounds — the nightly job. A found crash
//     fails the task AND saves the reproducer into the inputs
//     directory, where committing it turns it into a permanent
//     regression test.
//
// The task stays in the default build graph (Kover wires Test tasks
// into verification), so the mode is property-driven rather than a
// second task — a build must never unexpectedly spend 12 minutes
// fuzzing.
//
// The Phase 5 seeded harness (ParserFuzzTest / ClassifierFuzzTest)
// remains untouched: it pins controlled-outcome + determinism
// invariants; Jazzer complements it with coverage-guided exploration.

plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":openapi-parser"))
    implementation(project(":graphql"))
    implementation(project(":json-schema"))
    implementation(project(":registry"))
    implementation(project(":snapshot-store"))

    testImplementation(kotlin("test"))
    // Jazzer requires JUnit 5.9+; jazzer-junit brings the platform API
    // and runs under the root's useJUnitPlatform() configuration. The
    // API (FuzzedDataProvider) is not exposed transitively at compile
    // time and is declared explicitly.
    testImplementation("com.code-intelligence:jazzer-junit:0.23.0")
    testImplementation("com.code-intelligence:jazzer-api:0.23.0")
}

// Regression mode by default (the PR gate); coverage-guided fuzzing
// with `-Pjazzer.fuzz=1` (nightly).
tasks.register<Test>("jazzerFuzz") {
    description = "Jazzer targets: regression replay by default, coverage-guided fuzzing with -Pjazzer.fuzz=1"
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*JazzerTargets") }
    val fuzzMode = project.findProperty("jazzer.fuzz") == "1"
    environment("JAZZER_FUZZ", if (fuzzMode) "1" else "0")
    // Gradle's Test task does NOT include environment variables in its
    // up-to-date check — without this explicit input, the nightly fuzz
    // run would silently SKIP after any regression-mode execution.
    inputs.property("fuzzMode", if (fuzzMode) "fuzz" else "regression")
    testLogging { events("failed") }
}
