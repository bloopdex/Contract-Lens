# Contributing to ContractLens

Contributions are welcome; this file states the ground rules and the
practical expectations.

## Prerequisites

- JDK 17+ (the build uses a 17 toolchain; CI also tests on 21).
- PowerShell 5.1+ for the release/Action scripts (Windows); the
  project itself builds on any OS Gradle supports.

## Ground rules (non-negotiable)

- **Never weaken tests to make CI green.** A failing test means the
  code or the test is wrong; fix the right one.
- **Never fabricate evidence.** No invented CI results, benchmark
  numbers, fuzz sweeps, or security findings. If something cannot be
  verified, say so.
- **Never mark unfinished work DONE.** Delivery records are
  checklists, not decorations.
- **Every meaningful bug ships with a regression test where
  practical.** Fixtures and expected outputs are reviewed like code.
- **Document discrepancies, don't silently resolve them.** When a
  design note and the repository disagree, record the disagreement and
  the decision where the design lives.
- **Exit codes are a contract.** `0` success, `1` breaking changes
  detected, `2` operational error. Don't repurpose code 1.
- **Dependencies resolve strictly** — `--dependency-verification=strict`
  in CI; verification metadata is regenerated only after a deliberate,
  reviewed upgrade.

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

The architectural decisions are in [docs/adr/](docs/adr/) — see
[docs/adr/INDEX.md](docs/adr/INDEX.md). Before a load-bearing change:

- check the ADR index;
- write **or amend** an ADR for any decision that changes product
  behavior. Amendments are appended to the existing ADR, dated and
  attributed — decisions are never silently rewritten;
- if a decision involved research (alternatives were genuinely
  compared), add or extend the corresponding record in
  [docs/research/](docs/research/). Do not invent research,
  alternatives, or measurements — if the evidence is thin, the record
  says so.

## Documentation expectations

- The docs tree is organized by concern — start from
  [docs/index.md](docs/index.md) and keep it current when adding a
  document.
- Documentation claims must be verifiable: recorded measurements carry
  their date, environment, and harness; security claims are
  test-pinned or not made.
- **Implementation wins over stale documentation.** If a doc and the
  code disagree, fix the doc in the same change as the code.
- No documentation theater: a document exists because it answers an
  engineering question, not to complete a checklist.

## Commit conventions

- One logical change per commit; no automated co-author trailers.
- Commit messages state what and why; verification evidence goes into
  the delivery records, not prose claims.
- Tags are maintainer-only; automation never creates or moves them.
