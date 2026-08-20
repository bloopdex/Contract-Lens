plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // 2.1.44 (OSV scan): 2.1.40 fixed GHSA-2237-hv52-mmg9
    // (thread-safety race, security review) but its transitive graph
    // still resolved jackson 2.21.1 (multiple GHSAs, incl. HIGH
    // GHSA-r7wm-3cxj-wff9) and logback 1.3.15 (GHSA-25qh-j22f-pwp8).
    // 2.1.44 is the latest release (2026-06-12) with a newer
    // swagger-core graph. The full parser + fixture suites re-run
    // after the bump; any remaining advisories are handled per the
    // SECURITY.md policy (see docs/security.md).
    implementation("io.swagger.parser.v3:swagger-parser:2.1.44")
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}
