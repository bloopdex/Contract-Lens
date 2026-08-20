# Fuzzing ContractLens (seeded harness + Jazzer)

Two complementary layers. Neither replaces the other.

## 1. The seeded harness

`ParserFuzzTest` (`:cli:fuzz`) and `ClassifierFuzzTest` (`:core:fuzz`)
fuzz with a FIXED seed over a corpus of committed fixtures plus inline
seeds, applying byte flips / insertions / deletions / truncations /
duplications / span swaps, and assert per input:

- the outcome is controlled — a parsed result or a typed
  `ContractError`, never a crash;
- determinism — the same input twice produces the same outcome;
- failing inputs are saved as `fuzz-failure-<label>-#<index>.bin`.

Recorded sweeps: 200k parser iterations × 5 boundaries =
1,000,000 parser executions (39 min), and 200k classifier invariant
iterations (55 s) — both clean. Iteration counts come from the
`fuzz.iterations` system property (`-PfuzzIterations=…`).

## 2. Jazzer coverage-guided targets

`:fuzz` is a test-only module with `@FuzzTest` targets
(`com.code-intelligence:jazzer-junit` 0.23.0):

| Target | Boundary | Why |
|---|---|---|
| `fuzzOpenApi` | OpenAPI documents | untrusted input |
| `fuzzGraphQl` | GraphQL SDL | untrusted input |
| `fuzzJsonSchema` | JSON Schema events | untrusted input |
| `fuzzRegistry` | consumer registry YAML | untrusted input |
| `fuzzUsageGraph` | usage graph YAML | untrusted input |
| `fuzzSnapshotRoundTrip` | snapshot build → hash → serialize → verify | model integrity |

Target selection is deliberate, not exhaustive. The diff engine,
classifier, and mapper take typed, already-validated domain data — not
bytes — so a Jazzer target there would only fuzz our own scaffolding;
their invariants are already pinned by the 200k-iteration classifier
sweep. Every target is bounded (`maxDuration = "2m"`), writes only one
reused temp file, makes no network calls, and treats typed
`ContractError`s as NORMAL outcomes (only an untyped exception or a JVM
crash is a finding).

Seed corpora live in
`fuzz/src/test/resources/…/ParserJazzerTargetsInputs/<method>/`. When a
fuzz run finds a crash, Jazzer writes the reproducer into the target's
inputs directory — **committing that file turns it into a permanent
regression test** replayed by `:fuzz:jazzerFuzz` in its default (regression) mode.

### Local commands

```
gradlew :fuzz:jazzerFuzz                     # regression replay of committed crash inputs (seconds)
gradlew :fuzz:jazzerFuzz -Pjazzer.fuzz=1     # coverage-guided, 2m per target
gradlew :cli:fuzz -PfuzzIterations=200000    # recorded harness sweep
gradlew :core:fuzz -PfuzzIterations=200000
```

### CI policy

- PR: `:fuzz:jazzerFuzz` in regression mode (fast replay — also part of
  every build) + a short harness smoke — blocking.
- Nightly: `:fuzz:jazzerFuzz -Pjazzer.fuzz=1` (bounded, all targets) +
  the long harness sweeps. A found crash FAILS the scheduled run and
  preserves the reproducer; the fix flow is: reproduce locally → root
  cause → fix → commit the reproducer as a regression seed. Fuzz
  timeouts are never raised to make a failure disappear.
