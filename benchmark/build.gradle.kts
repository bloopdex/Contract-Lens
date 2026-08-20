plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":cli"))
    implementation(project(":core"))
    implementation(project(":openapi-parser"))
    implementation(project(":snapshot-store"))
    implementation(project(":registry"))
    implementation(project(":generated-client"))
    implementation(project(":graphql"))
    implementation(project(":json-schema"))

    // the comparison policy is a pure function and is unit-tested
    // (kotlin-test on the JUnit 5 platform, matching the root config)
    testImplementation(kotlin("test-junit5"))
}

// Performance baselines: `gradlew :benchmark:bench` runs the
// measured scenarios and REWRITES docs/benchmarks/baseline.json (a
// conscious maintainer action — never automatic).
tasks.register<JavaExec>("bench") {
    description = "Runs the ContractLens performance baseline scenarios and rewrites the committed baseline"
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.bloopdex.contractlens.benchmark.BenchmarkMainKt")
}

// PR smoke — reduced timing runs, writes nothing; proves the
// harness and fixtures still run without committing numbers.
tasks.register<JavaExec>("benchSmoke") {
    description = "Benchmark smoke: reduced timing runs, writes nothing (the PR gate)"
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.bloopdex.contractlens.benchmark.BenchmarkMainKt")
    args("smoke")
}

// Nightly comparison against the committed baseline; exit 1 on
// a FAIL-level regression (policy in BenchmarkCheck.kt). Never rewrites
// the baseline.
tasks.register<JavaExec>("benchCheck") {
    description = "Runs the benchmark suite and compares against the committed baseline (exit 1 on FAIL-level regression)"
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.bloopdex.contractlens.benchmark.BenchmarkMainKt")
    args("check")
}
