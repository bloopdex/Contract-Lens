# ContractLens performance baselines (Phase 5)

## Methodology

`gradlew :benchmark:bench` runs every scenario with 3 warmup runs and
7 timed runs; the recorded value is the **median** (the minimum is also
reported for context). Scenarios are deterministic:

- **openapi-parse-5k** — a generated 7,565-line OpenAPI 3.0 document
  (120 paths × GET/POST, nested object/array schemas, enums, path/query
  parameters) parsed into the canonical model.
- **openapi-diff-5k** — the 5k spec against its mutated twin (~96
  structural changes: path-template renames, required-property
  additions).
- **classify-diff** — the ADR-001 classifier over that change set.
- **impact-1k-consumers** — consumer mapping with a 1,000-consumer
  wildcard registry.
- **generated-projection-diff** — TS-style projection + diff of both
  5k surfaces.
- **graphql-parse** — SDL with 200 query fields and 200 object types.
- **json-schema-parse** — an event schema with 1,000 properties.
- **registry-parse-1k** — kaml parse + validation of a 1,000-consumer
  registry YAML.
- **snapshot-build-verify** — build + content-hash + serialize a
  snapshot of the 5k surface.

Fixture note: every generated path has a DISTINCT canonical identity
(`/group<N>/{resM}`). Sibling templates that share an identity are the
same operation by ADR-001, which would collapse the corpus — a
degenerate-fixture bug found while building the baseline, not an engine
defect.

The results are written to `docs/benchmarks/baseline.json` on every
run; the human-readable table goes to stdout. Phase 6 wired the same
scenarios into nightly CI as regression benchmarks (`:benchmark:benchCheck`).

## Phase 6 automation (2026-08-19)

- `:benchmark:bench` — full suite; REWRITES the committed baseline (a
  conscious maintainer action, never automatic).
- `:benchmark:benchSmoke` — reduced timing runs, writes nothing (the PR
  gate).
- `:benchmark:benchCheck` — full suite + comparison against the
  committed baseline, exit 1 on a FAIL-level regression.

Comparison policy (unit-tested in `BenchmarkCheckTest`):

- **FAIL** when a scenario is BOTH >3.0× its committed baseline median
  AND >1000 ms, or any scenario exceeds 2000 ms absolute.
- **WARN** (investigate, never silently ignored) at 2.0×-3.0×, or when a
  scenario has no committed baseline.
- The FAIL gate applies only on the OS family of the committed baseline
  (Windows); cross-OS runs are informational by construction — different
  OSes are never compared as equivalent measurements.
- Baselines are only rewritten by a conscious `:benchmark:bench` run.

Rationale: the scenarios measure 1-50 ms operations; shared-runner
scheduling/GC noise routinely doubles a run, so a bare ratio would fail
on noise. The 3× + 1 s floor catches real regressions while letting
noise through; the WARN band keeps intermediate regressions visible.
First local comparison run: 8 OK, 1 WARN (registry-parse-1k, 2.89× on a
busy machine) — the intended behavior. CI benchmark environments
(GitHub windows-latest) differ from this workstation baseline and are
recorded in the nightly results artifacts.

## Baseline (2026-08-19)

Environment: Windows 11 (10.0), Java 17.0.17 (Temurin), 16 CPUs, ~4 GB
max heap. Local workstation; numbers are for relative comparison, not
absolute claims.

| Scenario | Median | Min |
|---|---|---|
| openapi-parse-5k (7,565 lines) | 49.40 ms | 48.09 ms |
| openapi-diff-5k | 5.88 ms | 4.60 ms |
| classify-diff (96 changes) | 1.00 ms | 0.57 ms |
| impact-1k-consumers | 41.49 ms | 35.62 ms |
| generated-projection-diff | 12.35 ms | 10.81 ms |
| graphql-parse (200 types) | 8.05 ms | 6.97 ms |
| json-schema-parse (1,000 props) | 2.81 ms | 2.01 ms |
| registry-parse-1k | 29.32 ms | 27.64 ms |
| snapshot-build-verify | 14.93 ms | 9.76 ms |

Against the Phase 5 page's provisional targets (recorded, not claimed
as guarantees): parse + diff of a 5k-line spec ≈ **55 ms combined
(well under the 1 s target)**; registry resolution for 1k consumers
≈ **41 ms (under the 100 ms target)**. No optimizations were applied —
the baseline is sufficient on this evidence, and optimizing without a
measured bottleneck would be vanity work.
