# Contributing to ContractLens

ContractLens is part of the BloopLab ecosystem (a personal engineering
lab). Contributions are welcome; this file states the ground rules.

## Ground rules (non-negotiable)

- **Never weaken tests to make CI green.** A failing test means the
  code or the test is wrong; fix the right one.
- **Never fabricate evidence.** No invented CI results, benchmark
  numbers, fuzz sweeps, or security findings. If something cannot be
  verified, say so.
- **Never mark unfinished work DONE.** The Phase Definition-of-Done
  lists are checklists, not decorations.
- **Document discrepancies, don't silently resolve them.** When a spec
  page and the repository disagree, record the disagreement and the
  decision on the phase page (precedent: Phase 4's ADR-006 numbering
  collision, Phase 5's scope reconciliation).
- **Exit codes are a contract.** `0` success, `1` breaking changes
  detected, `2` operational error. Don't repurpose code 1.

## Build and verify

```
.\gradlew.bat build            # compile + ktlintCheck + the full test suite
.\gradlew.bat ktlintFormat     # auto-format before committing
.\gradlew.bat koverVerify      # the coverage gate (per-module minimums)
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000     # fuzz smoke
.\gradlew.bat :fuzz:jazzerFuzz                 # Jazzer crash-input replay (regression mode)
.\gradlew.bat :benchmark:benchSmoke               # benchmark smoke
.\gradlew.bat :benchmark:benchCheck               # benchmark vs committed baseline
```

See [docs/ci.md](docs/ci.md) for the CI architecture and its local
equivalents, [docs/fuzzing.md](docs/fuzzing.md) for the fuzz layers,
and [docs/coverage.md](docs/coverage.md) for the coverage policy.

## Design records

The design record (ADR-001..006 and the phase pages) lives in the
BloopLab Logseq graph; Phase 6 ADRs live in [docs/adr/](docs/adr/).
Before a load-bearing change: check the ADR index, and write (or amend)
an ADR for decisions that change product behavior.

## Commit conventions

- One logical change per commit; no AI-co-author trailers.
- Commit messages state what and why; verification evidence goes on the
  phase page, not in prose claims.
- Tags are maintainer-only; automation never creates or moves them.
