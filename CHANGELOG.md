# Changelog

All notable changes to ContractLens are documented here. Versioning is
semver per the classifier's own ruleset (breaking → major); releases are
tag-driven (`vX.Y.Z`) per ADR-007.

The detailed per-phase record lives in the BloopLab Logseq graph
(phase pages) and in `BloopLab-SOURCE-OF-TRUTH.md`; this file is the
concise artifact-facing history.

## Unreleased (Phase 6 — Production Delivery, CI & Continuous Verification)

- CI architecture: PR pipeline (build/test matrix, lint, coverage gate,
  security, fuzz smoke, benchmark smoke), nightly pipeline (long fuzz
  sweeps, Jazzer coverage-guided fuzzing, full benchmarks with a
  regression gate, action end-to-end test), tag-driven release workflow
  with strict tag↔version verification, checksum verification, and
  artifact smoke tests. Publication pending the repo hosting decision.
- Coverage-guided fuzzing: `:fuzz` module with six Jazzer
  `@FuzzTest` targets (five parser boundaries + snapshot round-trip);
  seeded harness from Phase 5 unchanged.
- Supply chain: Gradle dependency locking (`gradle.lockfile` per
  module) + strict dependency verification
  (`gradle/verification-metadata.xml`, `--dependency-verification=strict`
  in CI) + OSV-Scanner with a documented failure policy (SECURITY.md).
  First scan fixed real findings: jackson 2.21.1 (HIGH 8.7/8.1) →
  2.21.5, swagger-parser 2.1.40 → 2.1.44, logback 1.3.15 → 1.5.34,
  log4j-api 2.26.0 → 2.26.1, commons-lang3 3.17.0 → 3.18.0 (buildscript
  graph); one documented time-bounded waiver (kotlin-gradle-plugin,
  fix only in a beta). Final scan clean.
- Benchmarks: `:benchmark:benchSmoke` (PR) and `:benchmark:benchCheck`
  (nightly comparison with the documented regression policy — never
  rewrites the baseline).
- Packaging & release: fat JAR as the primary artifact (shadow),
  `scripts/release.ps1` + `release.yml`, SHA-256 checksums,
  install scripts, Docker image, reproducibility double-build
  verification (ADR-007).
- Versioning: `version` in the root build file is the single source;
  the CLI now reports it via `--version` (generated constant).
- DeployScore: `contractlens-signal` v1 payload + `contractlens signal`
  offline emitter, metadata-only with a pinned privacy boundary
  (ADR-008); no remote integration until DeployScore's API exists.
- UX fix: usage errors are never silent — the CLI always prints a
  diagnostic line on exit 2.

## 0.1.0 (Phases 0-5)

- Phase 0-5 scope: canonical contract model; OpenAPI 3.0/3.1 parsing;
  snapshot store (SHA-keyed, content-hash verified); structural diff
  engine (26-kind taxonomy); ADR-001 classifier (breaking /
  non-breaking / review + semver labels); explicit consumer registry +
  impact mapping; generated-client projection diffing (ADR-006);
  GraphQL SDL and JSON Schema adapters; usage-graph format (not wired
  into classification — deferred with evidence).
- Phase 5 hardening: 10 MB input limits (INPUT_TOO_LARGE) on all five
  boundaries; snapshot-store path-escape and redaction regression
  tests; seeded fuzz harnesses with recorded clean sweeps (1M parser
  executions, 200k classifier iterations); benchmark module + committed
  baseline; structured analysis metrics; Kover coverage gate;
  swagger-parser 2.1.40 (GHSA-2237-hv52-mmg9).
