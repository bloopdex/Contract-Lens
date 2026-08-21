# CI architecture

Three workflows: **CI** (PRs + pushes to master + tag pushes), **Nightly**
(scheduled), **Release** (tag-driven). All three execute on GitHub
Actions (first hosted runs 2026-08-21); the first tag push published
the v1.0.0 release end-to-end.

## Workflows and triggers

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | PR + push to master + tag push `v*` | build-test (matrix), coverage, security, fuzz-smoke, benchmark-smoke — every version tag runs the full validation matrix |
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

The FAIL/WARN thresholds and their rationale live in
[performance.md](performance.md) (the unit-tested policy in
`BenchmarkCheck.kt` is the implementation). CI specifics: the
comparison gate runs nightly on the baseline's OS family
(cross-OS runs are informational by construction), and baselines are
ONLY rewritten by a conscious maintainer run (`:benchmark:bench`) —
never automatically, never to make a comparison pass.

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
docker run --rm -v "${PWD}:/src" -w /src ghcr.io/google/osv-scanner scan --config=osv-scanner.toml -r .
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000 :core:fuzz -PfuzzIterations=5000
.\gradlew.bat :fuzz:jazzerFuzz                        # regression replay
.\gradlew.bat :fuzz:jazzerFuzz -Pjazzer.fuzz=1        # coverage-guided
.\gradlew.bat :benchmark:benchSmoke
.\gradlew.bat :benchmark:benchCheck
powershell -File scripts\test-action.ps1              # action e2e
powershell -File scripts\release.ps1 -Version 1.0.0   # release bundle (local)
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

- Dependabot version updates run on the repository (see
  [CONTRIBUTING.md](../CONTRIBUTING.md) for the deliberate-upgrade
  process — every dependency change must regenerate the committed
  verification metadata).
- The benchmark FAIL gate is intentionally conservative at ms scale —
  intermediate regressions surface as WARNs and need human eyes.
- The PR-comment step of the reusable Action is GitHub-runner-only
  (`gh` + `GITHUB_TOKEN`); the analysis core runs identically offline.
- CI matrix runs are green on both OSes only since the `gradlew`
  executable-bit fix (2026-08-21) — recorded in the first-hosted-run
  repair history.
