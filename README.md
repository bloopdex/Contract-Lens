# ContractLens

Local-first API contract impact-analysis tool: detect contract changes,
classify them as breaking / non-breaking / review, and explain which
known consumers may be affected — before merge.

**Status:** Phase 6 (Production Delivery, CI & Continuous
Verification). The pipeline is complete — phases 0-5 built the
analysis tool, Phase 6 added the CI architecture, coverage-guided
fuzzing, dependency/supply-chain automation, benchmark regression
gating, packaging & release with checksums, and the DeployScore signal
emitter. GitHub execution and publication are pending the BloopLab repo
hosting decision; everything runs locally and is verified (see
[docs/ci.md](docs/ci.md)).

## What it does

- Canonical contract model (`:core`) — one format-neutral representation
  with source locations for explainability.
- OpenAPI 3.0/3.1 parser (`:openapi-parser`), GraphQL SDL adapter
  (`:graphql`), JSON Schema event adapter (`:json-schema`) — local
  `$ref`s only, cycle/depth guards, 10 MB input limit, typed errors.
- File-backed snapshot store (`:snapshot-store`) — snapshots keyed by
  contract + git commit SHA, content-hash verified, corruption refused
  loudly.
- Structural diff engine — 26-kind change taxonomy with recursive
  leaf-level locations; renames never inferred.
- ADR-001 classifier — direction-aware verdicts
  (breaking / non-breaking / review) with deterministic reasons and
  derived semver labels (`diff --classify`, exit 1 on breaking).
- Consumer registry (ADR-002) + impact mapping — explicit, versioned,
  local-first YAML; "affected" means "declares consumption of the
  changed surface" (unregistered consumers are invisible — stated
  honestly in every report).
- Generated-client projection diffing (ADR-006) —
  `generated-diff --style ts|kotlin|java`.
- Usage graph format — versioned + validated, deliberately NOT wired
  into classification (no real usage data exists; documented).
- DeployScore signal (ADR-008) — `contractlens signal` emits the
  metadata-only `contractlens-signal` v1 payload to stdout or a file;
  offline by construction.

## Install

Requires a JRE 17+.

**Release JAR** (the primary artifact, ADR-007):

```
# download contractlens-<version>-all.jar and SHA256SUMS from the release
# verify FIRST:
powershell "(Get-FileHash -Algorithm SHA256 contractlens-0.1.0-all.jar).Hash.ToLowerInvariant()"
# compare against the SHA256SUMS line, then:
java -jar contractlens-0.1.0-all.jar --version
```

**Install scripts** (from the release bundle): `install.ps1`
(Windows), `install.sh` (Linux/macOS) — install the JAR + a shim,
verify the checksum first, PATH update is opt-in. `uninstall.ps1`
reverses it.

**Docker** (CI/container use):

```
docker build -t contractlens:0.1.0 .
docker run --rm -v "$PWD:/work" -w /work contractlens:0.1.0 diff \
    old.snapshot.json new.snapshot.json --classify
```

**From source**:

```
.\gradlew.bat :cli:installDist
cli\build\install\contractlens\bin\contractlens.bat --version
```

## Build, test, verify

```
.\gradlew.bat build                       # compile + ktlint + ALL tests
.\gradlew.bat ktlintFormat                # auto-format
.\gradlew.bat koverVerify                 # coverage gate (per-module minimums)
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000          # seeded fuzz smoke
.\gradlew.bat :fuzz:jazzerFuzz                        # Jazzer crash replay
.\gradlew.bat :fuzz:jazzerFuzz -Pjazzer.fuzz=1        # Jazzer fuzzing (2m/target)
.\gradlew.bat :benchmark:benchSmoke                   # benchmark smoke
.\gradlew.bat :benchmark:benchCheck                   # benchmark vs baseline
```

How CI validates the project, and how a release is produced, are
documented in [docs/ci.md](docs/ci.md) and
[docs/release.md](docs/release.md) — including the local equivalent of
every CI step.

## Usage

```
contractlens snapshot api/openapi.yaml --store .contractlens/snapshots
contractlens snapshot verify .contractlens/snapshots/users@<sha>.snapshot.json
contractlens snapshot list --store .contractlens/snapshots
contractlens diff old.snapshot.json new.snapshot.json
contractlens diff old.snapshot.json new.snapshot.json --classify      # verdicts + exit 1 on breaking
contractlens impact old.snapshot.json new.snapshot.json --registry registry.yaml
contractlens generated-diff old.snapshot.json new.snapshot.json --style ts --classify
contractlens signal old.snapshot.json new.snapshot.json --registry registry.yaml   # DeployScore feed
contractlens snapshot api.graphql                          # GraphQL SDL (extension-detected)
contractlens snapshot event.json --format json-schema      # JSON Schema event contract
contractlens --version
```

Exit codes: `0` success, `1` breaking changes detected
(`diff --classify`, `impact`, `generated-diff --classify`, `signal`),
`2` operational error. stdout carries output only; structured logs
(JSON lines, incl. the analysis metrics) go to stderr.

## The pre-merge GitHub Action

`action.yml` is a reusable composite action: snapshot base → snapshot
head → analysis → fail on breaking changes (optional PR comment,
opt-in). Inputs: `old-spec`, `new-spec`, `registry` (optional),
`contract-name` (optional), `fail-on-breaking` (default true),
`comment-on-pr` (default false). Outputs: `report`, `breaking`.
The analysis core runs offline and identically locally — the
pass/block/registry/no-fail cases are exercised by
`scripts/test-action.ps1` and the nightly `action-e2e` job.

## Registry (v1)

```yaml
version: 1
consumers:
  - id: thornwa-frontend          # stable identity — must be unique
    kind: frontend                # frontend | service | sdk | generated-client | integration
    contract: thorn-api
    operations:                   # "*" (all) or METHOD + path-template
      - GET /users/{id}
    contact: frontend team        # optional
    notes: optional free-form     # optional
```

## Security

Local-first: no network code path exists. Input limits, store-path
sanitization, redaction-by-construction, and typed failures are
reviewed in [docs/security.md](docs/security.md); the vulnerability
policy, reporting channel, and dependency-scanning policy are in
[SECURITY.md](SECURITY.md). Dependency resolution integrity is enforced
(`--dependency-verification=strict` against committed verification
metadata) and OSV-Scanner runs over the committed lockfiles.

## Known limitations

- Local `$ref`s only; multi-file/remote references rejected
  (`UNSUPPORTED_REFERENCE`).
- Inputs bounded by `MAX_INPUT_BYTES` (10 MB) + per-adapter depth
  guards.
- `x-stability-level` exemptions not implemented (the canonical model
  carries no stability levels).
- The usage graph is validated but NOT wired into classification —
  deferred with evidence (no real usage data exists; documented on the
  Phase 5 page).
- Generated-client projection is convention-stable, not byte-exact
  generator output; model TYPE names are not reported.
- `impact` requires both snapshots to share one contract name
  (`CONTRACT_MISMATCH` otherwise); contract renames are not mapped.
- Strict OpenAPI validation refuses real-world dumps with undeclared
  path parameters (found on thorn-api's NestJS dump) — correct loud
  failure; the fix belongs to the spec source.
- CI workflows and release publication execute only once the repo is
  hosted (BloopLab open decision #3); every step is verified locally.
- The DeployScore feed is a producer-side contract only — the receiving
  system does not exist yet (ADR-008).

## Documentation

- [docs/ci.md](docs/ci.md) — CI architecture, failure policy, local equivalents
- [docs/release.md](docs/release.md) — release process + checksum verification
- [docs/fuzzing.md](docs/fuzzing.md) — the seeded harness + Jazzer layers
- [docs/benchmarks.md](docs/benchmarks.md) — methodology + recorded baseline
- [docs/coverage.md](docs/coverage.md) — coverage policy and gate rationale
- [docs/security.md](docs/security.md) — security review and findings
- [docs/deployscore-feed.md](docs/deployscore-feed.md) — the signal contract
- [docs/adr/INDEX.md](docs/adr/INDEX.md) — ADR index (ADR-001..006 live
  in the BloopLab Logseq graph; ADR-007/008 in this repository)
- [CONTRIBUTING.md](CONTRIBUTING.md) — ground rules, incl. "never
  weaken tests to make CI green" and "never fabricate evidence"
