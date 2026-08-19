# ContractLens coverage policy (Phase 5)

## Tooling

Kover 0.9.9 (`org.jetbrains.kotlinx.kover`), applied to every module.
`gradlew koverXmlReport` produces per-module reports and the root
aggregate (`build/reports/kover/report.xml`); `gradlew koverVerify`
enforces the per-module gate below.

## Baseline (2026-08-19, clean instrumented run)

| Module | Line coverage |
|---|---|
| core | 85.8% (5,125 / 5,970) |
| openapi-parser | 94.4% |
| snapshot-store | 94.4% |
| registry | 88.9% |
| generated-client | 100.0% |
| graphql | 90.9% |
| json-schema | 95.5% |
| cli | 85.9% |
| **aggregate** | **88.5%** (10,890 / 12,310) |

## Gate

Per-module line-coverage minimums, set ~4-5 points below the measured
baseline (root `build.gradle.kts`, `coverageBounds`):

- core 85, openapi-parser 90, snapshot-store 90, registry 85,
  generated-client 95, graphql 85, json-schema 90, cli 80
- the benchmark module carries no gate (tool module)

`gradlew koverVerify` runs the gate; it is intended for CI (Phase 6
wires it in) and is reproducible locally.

## Rationale

The gate exists to prevent regression in critical domain logic, not to
inflate tests. The critical packages are:

- `core.diff` + `core.classify` + `core.impact` + `core.registry` +
  `core.usage` — the correctness core (diff identity, the ADR-001
  ruleset, mapping semantics, validation) — already heavily pinned by
  fixtures, property tests, and fuzz sweeps
- the four parser adapters — untrusted-input boundaries
- `snapshot-store` — integrity verification
- CLI commands — the exit-code and output contracts

Line coverage is the gated metric; branch coverage is reported but not
gated (combinatoric coverage tends to inflate meaningless tests). The
buffers keep routine refactors from tripping the gate while still
catching large, silent drops in critical-logic coverage.
