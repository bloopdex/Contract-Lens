# Contributing to ContractLens

Contributions are welcome; this file states the ground rules.

## Ground rules (non-negotiable)

- **Never weaken tests to make CI green.** A failing test means the
  code or the test is wrong; fix the right one.
- **Never fabricate evidence.** No invented CI results, benchmark
  numbers, fuzz sweeps, or security findings. If something cannot be
  verified, say so.
- **Never mark unfinished work DONE.** The Definition-of-Done lists in
  the delivery records are checklists, not decorations.
- **Document discrepancies, don't silently resolve them.** When a
  design note and the repository disagree, record the disagreement and
  the decision where the design lives (precedent: the ADR-006 numbering
  collision, and the scope reconciliations recorded with the delivery
  notes).
- **Exit codes are a contract.** `0` success, `1` breaking changes
  detected, `2` operational error. Don't repurpose code 1.

## Build and verify

```
.\gradlew.bat build                       # compile + ktlint + ALL tests
.\gradlew.bat ktlintFormat                # auto-format
.\gradlew.bat koverVerify                 # the coverage gate (per-module minimums)
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000     # fuzz smoke
.\gradlew.bat :fuzz:jazzerFuzz                    # Jazzer crash-input replay
.\gradlew.bat :benchmark:benchSmoke               # benchmark smoke
.\gradlew.bat :benchmark:benchCheck               # benchmark vs baseline
```

See [docs/ci.md](docs/ci.md) for the CI architecture and its local
equivalents, [docs/fuzzing.md](docs/fuzzing.md) for the fuzz layers,
and [docs/coverage.md](docs/coverage.md) for the coverage policy.

## Design records

The architectural decisions are indexed in
[docs/adr/](docs/adr/) — ADR-001..006 live in the project's research
notes, ADR-007/008 in this repository. Before a load-bearing change:
check the ADR index, and write (or amend) an ADR for decisions that
change product behavior.

## Commit conventions

- One logical change per commit; no AI-co-author trailers.
- Commit messages state what and why; verification evidence goes into
  the delivery records, not prose claims.
- Tags are maintainer-only; automation never creates or moves them.
