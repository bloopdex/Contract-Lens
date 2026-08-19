// ContractLens multi-module layout (ContractLens / Phase 1 - Core Foundation):
//   :core            — canonical contract model, normalization, errors (no IO, no CLI)
//   :openapi-parser  — OpenAPI 3.0/3.1 -> canonical model adapter (swagger-parser is an internal detail)
//   :snapshot-store  — file-backed snapshot persistence, indexing, integrity verification
//   :cli             — the contractlens executable (Clikt), structured logging, exit codes
//
// Dependency direction is strictly inward: cli -> snapshot-store -> core,
// cli -> openapi-parser -> core. Modules never depend upward.
rootProject.name = "contractlens"

include(":core", ":openapi-parser", ":snapshot-store", ":cli")
