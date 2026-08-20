# Performance

A research record, not a marketing table: what was asked, what was
measured, what the measurements did and did not prove, and what was
done with them.

## Question

What performance level does ContractLens need, and does the
implementation meet it? (Startup cost, parse, normalize, diff,
classify, consumer mapping, output generation.)

## Research

The recorded policy from the start: **measure first, set targets
second, and only on the measurement's own scale.** No targets were
invented before a baseline existed. The provisional targets were
anchored once numbers existed: parse + diff a 5k-line spec in under
1 s, and registry resolution for 1,000 consumers in under 100 ms —
both chosen as "comfortably interactive pre-merge check" thresholds,
then held against the baseline below.

## Method

`gradlew :benchmark:bench` runs nine deterministic scenarios, each
with 3 warmup runs and 7 timed runs; the recorded value is the
**median** (minimum reported for context). The fixtures are generated
in-process, deterministic by construction:

- a generated 7,565-line OpenAPI 3.0 document (120 paths × GET/POST,
  nested object/array schemas, enums, path/query parameters) and its
  mutated twin — the pair carries **96 structural changes**
  (template-variable renames and required-property additions, plus
  their knock-on kinds);
- a 1,000-consumer registry;
- 200-query-field / 200-type GraphQL SDL;
- a 1,000-property JSON Schema document.

Fixture note (a real finding, not trivia): every generated path must
have a **distinct canonical identity** (`/group<N>/{resM}`) — sibling
templates that share an identity are the *same operation* by ADR-001,
which would collapse the corpus into one operation. The first generator
produced 120 such siblings; the benchmark corpus was degenerate and the
diff was empty. The generator was fixed; the identity rule is why the
fixture note exists in the code.

## Environment

Recorded baseline (2026-08-19): Windows 11 (10.0), Java 17.0.17
(Temurin), 16 CPUs, ~4 GB max heap — a local workstation. The numbers
are for **relative comparison** (regression detection), not absolute
claims on any other machine.

## Results

Committed baseline: `docs/benchmarks/baseline.json`.

| Scenario | Median | Min |
|---|---|---|
| openapi-parse-5k (7,565 lines) | 49.40 ms | 48.09 ms |
| openapi-diff-5k (96 changes) | 5.88 ms | 4.60 ms |
| classify-diff (96 changes) | 1.00 ms | 0.57 ms |
| impact-1k-consumers | 41.49 ms | 35.62 ms |
| generated-projection-diff | 12.35 ms | 10.81 ms |
| graphql-parse (200 types) | 8.05 ms | 6.97 ms |
| json-schema-parse (1,000 props) | 2.81 ms | 2.01 ms |
| registry-parse-1k | 29.32 ms | 27.64 ms |
| snapshot-build-verify | 14.93 ms | 9.76 ms |

Against the provisional targets: parse + diff of the 5k spec ≈ **55 ms
combined** (well under the 1 s target); 1,000-consumer resolution ≈
**41 ms** (under the 100 ms target).

## Decision

**No optimizations were applied.** The baseline is sufficient on this
evidence, and optimizing without a measured bottleneck would be vanity
work. The scenarios were wired into automation instead — the
measurements earn their keep as regression detection:

- `:benchmark:bench` — full suite; REWRITES the committed baseline (a
  conscious maintainer action, never automatic).
- `:benchmark:benchSmoke` — reduced timing runs, writes nothing (the
  PR gate: proves the harness still executes).
- `:benchmark:benchCheck` — full suite + comparison against the
  committed baseline (nightly), exit 1 on a FAIL-level regression.

Comparison policy (unit-tested in `BenchmarkCheckTest`):

- **FAIL** when a scenario is BOTH >3.0× its committed baseline median
  AND >1000 ms, or any scenario exceeds 2000 ms absolute.
- **WARN** (investigate, never silently ignored) at 2.0×-3.0×, or when
  a scenario has no committed baseline.
- The FAIL gate applies only on the OS family of the committed
  baseline; cross-OS runs are informational by construction — different
  OSes are never compared as equivalent measurements.

## Why this policy

The scenarios measure 1-50 ms operations; shared-runner scheduling/GC
noise routinely doubles a run, so a bare ratio would fail on noise.
The 3× + 1 s floor catches real regressions while letting noise
through; the WARN band keeps intermediate regressions visible. First
local comparison run: 8 OK, 1 WARN (registry-parse-1k at 2.89× on a
busy machine) — exactly the intended noise-tolerant behavior.

## Consequences

- What the measurements prove: the tool is comfortably interactive for
  the fixture scale (thousands of lines, thousands of consumers) on the
  recorded machine; the harness is deterministic and the regression
  gate is enforced.
- What they do not prove: behavior on other machines or other OSes
  (cross-OS runs are informational), behavior beyond the fixture
  scales, or anything about pathological inputs (that is the
  [fuzzing.md](fuzzing.md) and [security.md](security.md) territory).
- Startup cost (JVM) is deliberately out of these scenarios — it was
  recorded as a metric to be *measured, not assumed* (ADR-005), and
  the fat-JAR decision (ADR-007) keeps native-image compilation a
  revisit-condition, not a plan.
- The comparison gate lives in CI per [ci.md](ci.md).
