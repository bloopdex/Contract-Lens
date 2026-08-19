// ContractLens multi-module layout (ContractLens / Phase 1 - Core Foundation):
//   :core            — canonical contract model, normalization, errors (no IO, no CLI)
//   :openapi-parser  — OpenAPI 3.0/3.1 -> canonical model adapter (swagger-parser is an internal detail)
//   :snapshot-store  — file-backed snapshot persistence, indexing, integrity verification
//   :registry        — consumer registry YAML adapter (kaml -> validated domain model)
//   :cli             — the contractlens executable (Clikt), structured logging, exit codes
//
// Dependency direction is strictly inward: cli -> snapshot-store -> core,
// cli -> openapi-parser -> core, cli -> registry -> core. Modules never
// depend upward.
plugins {
    // Auto-provisions the JDK 17 toolchain on machines that don't have it
    // (CI installs 17/21 explicitly via setup-java).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "contractlens"

include(":core", ":openapi-parser", ":snapshot-store", ":registry", ":cli")
