# Testing strategy

This page explains **why** each testing layer exists and what it
protects. Test counts are project facts (currently 329 tests / 0
failures on a clean build) — but the strategy is the layers, not the
number.

## Philosophy

- **Deterministic over probabilistic.** Every classification rule is
  pinned by fixture pairs of contract snapshots with expected
  verdicts, so ruleset changes are regression-tested before they ship.
- **Rules are contracts, not happy paths.** Every rule gets positive
  and negative fixtures — the non-breaking variant of a breaking case
  is tested too.
- **Every meaningful bug results in a regression test where
  practical.** Bugs found in verification are fixed *and* pinned; the
  delivery records list them one by one. Fixtures change only with
  deliberate ruleset changes and are reviewed like code.
- **Determinism is a requirement, not a property.** Identical inputs
  → identical snapshots, diffs, and reports — pinned by property tests
  and re-pinned at scale by fuzz sweeps.

## The layers

| Layer | Where | What it protects |
|---|---|---|
| Unit | every module (`src/test`) | parsers, domain model, classification rules, registry/usage validation, mapping, signal builder |
| Integration | `:snapshot-store`, `:cli` | snapshot persistence round-trips, store index rebuild, CLI commands, registry round-trips |
| Contract fixtures | `cli/src/test/resources/fixtures/{diff,classify,impact,generated}` | the end-to-end pipeline: real old/new documents through the real parser, engine, adapter, and mapper, against expected outputs reviewed like code. The 26-case classification corpus pins every rule; the diff and impact suites pin the structural and mapping behavior |
| Property-based | `*PropertyTest` (kotest-property) | invariants over generated schema mutations: canonicalization idempotence, diff determinism and direction-mirroring, classifier determinism/totality/purity/verdict-semver consistency, mapper determinism and no phantom consumers |
| Fuzz | `:cli:fuzz`, `:core:fuzz` (seeded) + `:fuzz` (Jazzer) | the untrusted-input boundaries never crash and stay deterministic — see [fuzzing.md](fuzzing.md) |
| Performance | `:benchmark` | the harness runs and the baseline is comparable — see [performance.md](performance.md) |
| Failure | `RobustnessTest` + per-command tests | corrupt input, missing files, oversized specs, unreadable paths, path escape, redaction |
| Coverage gate | Kover per-module minimums | silent, large drops in critical-logic coverage fail the build — see [coverage.md](coverage.md) |

## The fixture convention

Fixture spec pairs live under `cli/src/test/resources/fixtures`,
grouped by scenario (diff / classify / impact / generated), each with
old/new documents and an expected output file. Expected verdicts are
declared next to each pair and reviewed like code. Fixtures are
versioned in the repo; changing one is a deliberate ruleset change.

The convention exists because **the classification ruleset is the
product's correctness core**: a ruleset change without a fixture run is
a ruleset change without evidence.

## Property invariants

The property tests hold these invariants across generated schema
mutations (they were designed as acceptance criteria for each engine
component):

- diff: `diff(x, x)` is empty; byte-identical reproduction;
  `diff(canonical(x), canonical(y)) == diff(x, y)`; results always
  changeOrder-sorted; direction-mirror (reversed inputs mirror every
  change with inverse kind and swapped from/to).
- classifier: determinism, totality (every change gets a verdict),
  purity (inputs never mutated), verdict/semver consistency,
  rename-pairing determinism.
- mapper: determinism; registry ordering invariance; no phantom
  consumers; no phantom changes; duplicated changes never duplicate
  impacts; wildcard consumers receive every engine change.

## Regression policy

One fixture per rule; one per reported bug. The bug lists in the
delivery records are the evidence this policy was followed — each entry
names the test that pins the fix.

## Security regression tests

Security boundaries are test-pinned, not just documented:

- path escape from the snapshot store;
- redaction: secrets in descriptions/examples never appear in snapshot
  bytes or reports;
- oversized inputs fail with `INPUT_TOO_LARGE` at every boundary;
- tampered snapshots fail with `SNAPSHOT_INTEGRITY`.

See [security.md](security.md) for the full threat → boundary →
defense → verification map.

## CI test strategy

- **PR:** build + full test suite (JVM 17/21 × Linux/Windows), ktlint,
  coverage gate, strict dependency verification + OSV scan, fuzz smoke,
  Jazzer crash-input replay, benchmark smoke.
- **Nightly:** the expensive sweeps — long fuzz runs, coverage-guided
  Jazzer fuzzing, full benchmark comparison, the Action end-to-end
  test, a fresh OSV scan.

Workflow and failure semantics: [ci.md](ci.md).

## Local commands

```
.\gradlew.bat build                       # compile + ktlint + ALL tests
.\gradlew.bat ktlintFormat                # auto-format
.\gradlew.bat koverVerify                 # the coverage gate
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000     # seeded fuzz smoke
.\gradlew.bat :core:fuzz -PfuzzIterations=5000
.\gradlew.bat :fuzz:jazzerFuzz                    # Jazzer crash-input replay
.\gradlew.bat :fuzz:jazzerFuzz -Pjazzer.fuzz=1    # Jazzer fuzzing (2m/target)
.\gradlew.bat :benchmark:benchSmoke               # benchmark smoke
.\gradlew.bat :benchmark:benchCheck               # benchmark vs baseline
```

## Definition of test completeness

- Every rule in the classification ruleset has at least one fixture
  pair with a verdict.
- Fuzz and property runs are green; the seeded sweeps are re-run at
  scale after dependency upgrades.
- The coverage gate passes (per-module minimums).
- A performance baseline exists and the comparison policy is enforced.
- Every reported bug ships with a regression test.
