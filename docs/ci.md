# CI architecture

Three workflows: **CI** (PRs + pushes to main), **Nightly** (scheduled),
**Release** (tag-driven). Every command in every workflow has been
executed locally; GitHub execution itself is pending the repo hosting
decision (the repository hosting is undecided) — the workflows
run as written once the repository is pushed to a host.

## Workflows and triggers

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | PR + push to main | build-test (matrix), coverage, security, fuzz-smoke, benchmark-smoke |
| `nightly.yml` | cron `37 3 * * *` + manual dispatch | fuzz-long, jazzer-fuzz, benchmark (windows, gated), benchmark-ubuntu (informational), action-e2e, osv-scan |
| `release.yml` | tag push `v*.*.*` ONLY | release (verify tag ↔ version → full validation → package → checksums → smoke → publish) |

## PR pipeline (all blocking)

| Job | Command | Measured locally | Why blocking |
|---|---|---|---|
| build-test | `gradlew build` (JVM 17+21 × linux+windows) | ~2 min cached / fresh build ~4-6 min | the correctness gate: compile + ktlint + all tests (includes the fuzz tasks at default counts and the Jazzer regression replay) |
| coverage | `gradlew koverVerify koverXmlReport` | ~1-2 min | the coverage gate is policy, not decoration |
| security | `gradlew assemble --dependency-verification=strict` + OSV-Scanner over the committed lockfiles (policy in `osv-scanner.toml`) | ~20 s + scan | dependency integrity is enforced, not just scanned |
| fuzz-smoke | `gradlew :cli:fuzz -PfuzzIterations=5000 :core:fuzz -PfuzzIterations=5000 :fuzz:jazzerFuzz` | 1m23s + 30s + 21s | parser invariants on every change |
| benchmark-smoke | `gradlew :benchmark:benchSmoke` | 7 s | proves the harness still executes; writes nothing |

Total PR feedback ≈ 5-8 minutes wall-clock (jobs run in parallel;
Gradle build caching is enabled via setup-gradle).

## Nightly pipeline

| Job | What | Failure behavior |
|---|---|---|
| fuzz-long | 200k iterations per harness (the recorded sweep scale, ~39 min) | fails on any crash/nondeterminism; the harness saves failing inputs |
| jazzer-fuzz | `:fuzz:jazzerFuzz -Pjazzer.fuzz=1` — 6 coverage-guided targets, 2 min each | fails on a found crash; reproducer directory uploaded as an artifact (fix flow: reproduce → root cause → fix → commit reproducer as a regression seed) |
| benchmark (windows) | full suite + comparison vs committed baseline | FAIL only for catastrophic regressions (policy below); 2x-3x = WARN, printed + artifact |
| benchmark-ubuntu | same suite on Linux | informational by construction — cross-OS numbers are never compared as equivalent |
| action-e2e | the reusable Action's pass/block/registry/no-fail cases | fails on any case |
| osv-scan | fresh OSV database scan | per the SECURITY.md severity policy |

## Benchmark comparison policy

Implemented in `BenchmarkCheck.kt` (unit-tested):

- **FAIL** when a scenario is BOTH >3.0× its committed baseline median
  AND >1000 ms, OR any scenario exceeds 2000 ms absolute.
- **WARN** (investigate, never silently ignored) at 2.0×-3.0×, or when a
  scenario has no committed baseline.
- The FAIL gate applies only on the OS family of the committed baseline
  (windows); cross-OS runs are informational.
- Baselines are ONLY rewritten by a conscious maintainer run
  (`:benchmark:bench`) — never automatically, never to make a
  comparison pass.

Rationale: the scenarios measure 1-50 ms operations; shared-runner
scheduling/GC noise routinely doubles a run, so a bare ratio would fail
on noise. The 3× + 1 s floor catches real regressions while letting
noise through; the WARN band keeps intermediate regressions visible.
First local comparison run (2026-08-19): 8 OK, 1 WARN
(registry-parse-1k, 2.89× on a busy machine) — exactly the intended
behavior.

## Failure semantics (per stage)

| Stage | Failure means | Blocks? | Diagnosis |
|---|---|---|---|
| tests | a test failed | yes | per-test XML reports + logs |
| ktlint | style violation | yes | ktlint reports per module |
| coverage gate | module under its minimum | yes | koverVerify output names the module |
| dependency verification | resolved artifact not in the committed metadata | yes | Gradle lists the artifact — regenerate metadata only after a deliberate, reviewed upgrade |
| OSV scan | known vulnerability at the policy threshold | per severity policy (SECURITY.md) | scan output names CVE + fixed version |
| fuzz | crash or nondeterminism | yes | failing input saved; reproduce locally |
| jazzer fuzz (nightly) | found crash | fails scheduled run | reproducer artifact |
| benchmark regression | FAIL-level | fails scheduled run | comparison table + results artifact |
| DeployScore unavailable | N/A | never | there is no network path (ADR-008) |

## Local equivalents

Every CI stage has an identical local command — CI runs exactly what a
developer runs:

```
.\gradlew.bat build                                   # build-test
.\gradlew.bat koverVerify koverXmlReport              # coverage
.\gradlew.bat assemble --dependency-verification=strict
docker run --rm -v "${PWD}:/src" -w /src ghcr.io/google/osv-scanner scan -r .
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000 :core:fuzz -PfuzzIterations=5000
.\gradlew.bat :fuzz:jazzerFuzz                        # regression replay
.\gradlew.bat :fuzz:jazzerFuzz -Pjazzer.fuzz=1        # coverage-guided
.\gradlew.bat :benchmark:benchSmoke
.\gradlew.bat :benchmark:benchCheck
powershell -File scripts\test-action.ps1              # action e2e
powershell -File scripts\release.ps1 -Version 0.1.0   # release bundle (local)
```

## Permissions & CI security

- `ci.yml` / `nightly.yml`: `contents: read` — untrusted PR code never
  gets write access.
- `release.yml`: `contents: write`, and it runs ONLY on `v*.*.*` tag
  pushes (maintainer-only); never on branches or PRs. Release
  credentials never reach untrusted workflows.
- Actions are pinned (`@v4`, `@v1`); no step interpolates untrusted
  input into a shell — the Action core reads inputs exclusively via
  environment variables (scripts/action-check.ps1).

## Known CI limitations

- Workflows execute only once the repository is hosted (no remote yet);
  every command is verified locally with the results recorded in the
  the delivery record.
- Dependabot + the GitHub dependency graph activate with hosting; the
  OSV scan covers the gap locally.
- The benchmark FAIL gate is intentionally conservative at ms scale —
  intermediate regressions surface as WARNs and need human eyes.
- The PR-comment step of the reusable Action is GitHub-runner-only
  (`gh` + `GITHUB_TOKEN`); the analysis core runs identically offline.
