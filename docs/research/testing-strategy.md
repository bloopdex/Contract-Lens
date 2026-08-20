# Research record — the testing strategy

## Question

The classification ruleset is the product's correctness core. How do
you pin a compatibility ruleset so that **no ruleset change ships
without evidence**, and no bug recurs?

## Research

The strategy was designed alongside the ruleset (before
implementation), with each layer justified by what it must prove:

- **Fixture-driven rule testing:** the Phase-0 canonical change-case
  catalog (14 scenario groups) was the acceptance suite for the diff
  engine and classifier — one spec pair per rule, expected verdicts
  declared next to the pair. This became the fixture convention:
  fixtures reviewed like code, changed only with deliberate ruleset
  changes.
- **Property-based testing:** the engine invariants (diff
  determinism, direction-mirroring, canonicalization idempotence)
  are properties over generated schema mutations, not point checks —
  kotest-property.
- **Fuzzing justification:** YAML is historically hostile (anchor
  bombs, deep nesting) and specs are untrusted input, so the parser
  needed fuzz coverage beyond unit tests — first the seeded
  invariant-checking harness, later Jazzer coverage-guided targets
  (see [security-research.md](security-research.md)).
- **Regression-per-bug policy:** one fixture per reported bug — the
  delivery records list each bug with the test that pins its fix.

## Alternatives

- **Snapshot/golden testing only:** rejected — golden files pin
  *outputs*, not *properties*; the invariants (mirror, idempotence,
  totality) are what catch engine regressions early.
- **Coverage number as the goal:** rejected — the coverage gate is a
  regression floor for critical logic, deliberately set ~4-5 points
  below the measured baseline to avoid test inflation
  ([coverage.md](../coverage.md)).

## Decision

The layered strategy documented in [testing.md](../testing.md):
unit → integration → contract fixtures → property-based → fuzz →
performance → failure → coverage gate, with determinism as the
meta-requirement every layer re-pins.

## Why

Each layer proves one thing the others cannot: fixtures prove *the
rule* (did the expected verdict survive a change); properties prove
*the engine* (does it stay deterministic and total); fuzz proves *the
boundary* (does the parser never crash); the gate proves *the floor*
(coverage cannot silently collapse). No single layer carries all four.

## Implementation

- Fixture suites under `cli/src/test/resources/fixtures/{diff,
  classify, impact, generated}` (the 26-case classification corpus,
  the diff pairs, the impact triplets, the generated pairs).
- `*PropertyTest` invariant suites; the seeded fuzz harnesses
  (`:cli:fuzz`, `:core:fuzz`); the `:fuzz` Jazzer module.
- Kover per-module minimums (`coverageBounds`).
- `:benchmark` smoke/check modes as the performance regression layer.

## Verification

- Clean build: 329 tests / 0 failures across 10 modules; ktlint
  clean; koverVerify green.
- Recorded sweeps: 1,000,000 parser executions + 200,000 classifier
  iterations, clean; Jazzer 2,573,262 executions, 0 crashes.
- The benchmark corpus degeneracy (sibling templates sharing one
  canonical identity → empty diff) was caught by the harness design
  itself — an engine-semantics gotcha documented in
  [performance.md](../performance.md).

## Consequences

- Fixtures are reviewed like code: a ruleset change that alters an
  expected verdict is a visible, deliberate diff.
- Every bug in the delivery records ships with a regression test —
  the policy, not an aspiration.
- The coverage gate exists to prevent silent drops in critical-logic
  coverage, not to inflate numbers: branch coverage is reported but
  not gated.
