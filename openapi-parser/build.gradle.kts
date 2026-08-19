plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // 2.1.40: GHSA-2237-hv52-mmg9 (High) affects 2.1.15-2.1.38 — a
    // thread-safety race in OpenAPI 3.1 parsing. ContractLens parses
    // single-threaded, but the patched version removes the exposure
    // entirely (Phase 5 security review; see docs/security.md).
    implementation("io.swagger.parser.v3:swagger-parser:2.1.40")
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}
