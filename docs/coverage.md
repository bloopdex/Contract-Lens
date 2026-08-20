# ContractLens coverage policy

## Tooling

Kover 0.9.9 (`org.jetbrains.kotlinx.kover`), applied to every module.
`gradlew koverVerify` enforces the per-module gate below (the gate is
the enforcement — it runs in CI). Reports: each module writes
`<module>/build/reports/kover/report.xml`.

Measurement note (2026-08-20): the ROOT aggregate report
(`build/reports/kover/report.xml`) is now empty — Kover 0.9.9's root
merge yields no data when a subproject without main sources exists
(the new test-only `:fuzz` module). The gate is unaffected (per-module
rules); aggregate numbers below are the per-module reports summed, the
same scope as the gate. The CI coverage job uploads the per-module
reports.

## Baseline (2026-08-20, clean instrumented run; per-module report sums)

| Module | Line coverage |
|---|---|
| core | 86.5% (1,112 / 1,286) |
| openapi-parser | 94.1% (239 / 254) |
| snapshot-store | 94.4% (118 / 125) |
| registry | 88.9% |
| generated-client | 100.0% |
| graphql | 90.9% (149 / 164) |
| json-schema | 95.5% (105 / 110) |
| cli | 86.6% (496 / 573) |
| benchmark | 16.1% (tool module, no gate — most code executes only under `:benchmark:bench`) |
| **aggregate (gate scope, benchmark excluded)** | **88.7%** (2,323 / 2,618) |

(An earlier table listed aggregate 88.5% (10,890 / 12,310) computed
from the root report — those counters exceed the actual source sizes
(e.g. core has ~2,500 source lines), an artifact of the then-working
root merge. The percentages were always the meaningful number and they
agree: 88.5% then, 88.7% now.)

## Gate

Per-module line-coverage minimums, set ~4-5 points below the measured
baseline (root `build.gradle.kts`, `coverageBounds`):

- core 85, openapi-parser 90, snapshot-store 90, registry 85,
  generated-client 95, graphql 85, json-schema 90, cli 80
- the benchmark module carries no gate (tool module); the `:fuzz`
  module has no main sources

`gradlew koverVerify` runs the gate; it runs in CI and is reproducible locally.

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
