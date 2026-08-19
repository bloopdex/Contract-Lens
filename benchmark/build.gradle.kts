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
}

// Phase 5 performance baselines: `gradlew :benchmark:bench` runs the
// measured scenarios and writes docs/benchmarks/baseline.json. The
// methodology and the recorded numbers live in docs/benchmarks.md.
tasks.register<JavaExec>("bench") {
    description = "Runs the ContractLens performance baseline scenarios"
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.bloopdex.contractlens.benchmark.BenchmarkMainKt")
}
