# ContractLens security review (Phase 5 review + Phase 6 automation)

Status: reviewed surfaces + findings below. This is NOT a claim of
"security hardening complete" beyond what is written here; the threat
model lives on the Phase 0 page. Phase 6 added the automated layer
(SECURITY.md policy, dependency verification, OSV scanning, CI
permissions); the Phase 5 review below is the manual foundation.

## Reviewed surfaces and findings

### File / path handling
- The snapshot store sanitizes contract names before building file
  names (`SnapshotStore.sanitize`, Phase 1) — a contract named
  `../evil` cannot escape the store directory. **Pinned by a
  regression test in this phase** (`RobustnessTest`: "a contract name
  that looks like a path cannot escape the snapshot store").
- CLI file arguments must exist, be regular files, and be readable
  (Clikt `file()` validation); nothing follows symlinks specially.
- All paths are normalized (`toAbsolutePath().normalize()`) before use.

### Resource limits (NEW in Phase 5)
- `MAX_INPUT_BYTES` = 10 MB applied BEFORE parsing at every untrusted
  input boundary: OpenAPI documents, GraphQL SDL, JSON Schema events,
  registries, usage graphs. Oversized inputs fail with the typed
  `INPUT_TOO_LARGE` error. Regression-tested at every boundary.
- Schema nesting is bounded by each adapter's depth guard
  (`DEPTH_EXCEEDED` at depth 64) — OpenAPI (Phase 1), GraphQL and
  JSON Schema (Phase 4).
- The real thorn-api dump (~73 KB) is far under the limit; normal use
  is unaffected.

### `$ref` handling
- Local references only; multi-file and remote references are rejected
  with `UNSUPPORTED_REFERENCE` (Phase 1). No network resolution was
  introduced in Phase 5 and none is planned for the core.
- JSON Schema cross-document refs are rejected loudly; local refs stay
  REF nodes (Phase 4).
- Cycle/depth guards exist in every resolver.

### Parsers
- All four parsers fail safely and diagnostically on malformed input:
  every failure is a typed `ContractError` with a stable code; raw
  third-party exceptions never reach the CLI. Pinned by the Phase 5
  fuzz harness (parser fuzz: controlled-outcome + determinism
  invariants, 1M+ recorded parser executions).

### Redaction boundary
- The canonical model never captures descriptions, examples, or other
  raw spec prose (Phase 1 scope), so snapshots and reports cannot leak
  them. **Pinned by a regression test in this phase**
  (`RobustnessTest`: secrets in descriptions/examples do not appear in
  snapshot bytes or in diff/impact reports).

### CLI
- stdout carries command output only; structured logs and diagnostics
  go to stderr (logback configuration, Phase 1). Exit codes are the
  documented contract (0 / 1-breaking / 2-error).
- No command execution, no shell interpolation of contract content.

### Registry / usage graph
- kaml strict-mode decode: unknown fields fail (never silently
  reinterpreted); duplicate ids fail; selectors are validated against
  the canonical identity; duplicate usage operations merge
  deterministically (Phases 3-4, test-pinned).

### Dependencies
- Spot-checked the two most attack-relevant dependencies (August 2026):
  - `snakeyaml` 2.4 — 0 known direct vulnerabilities (all published
    CVEs predate 2.0; 2.4 is beyond the fixes).
  - `swagger-parser` 2.1.27 → **UPGRADED to 2.1.40** in this phase:
    GHSA-2237-hv52-mmg9 (High severity) affects 2.1.15-2.1.38 — a
    thread-safety race in OpenAPI 3.1 parsing that can swap parse
    results across threads. ContractLens parses single-threaded, but
    the patched version removes the exposure entirely. Parser and
    fixture suites re-verified green after the upgrade.
- Systematic dependency/supply-chain scanning is a Phase 6 CI item
  (documented on the Phase 5 page), along with checksummed releases.

## Phase 6 automation (implemented 2026-08-19)

- **Dependency verification** — every resolved artifact is SHA-256
  pinned in the committed `gradle/verification-metadata.xml`; CI
  resolves with `--dependency-verification=strict` (regenerated only
  after a deliberate, reviewed upgrade).
- **Dependency locking** — every module commits a `gradle.lockfile`;
  updates go through Dependabot PRs once hosted.
- **Vulnerability scanning** — OSV-Scanner over the committed lockfiles
  (CI jobs + a documented local docker invocation); the failure policy
  per severity is in SECURITY.md (critical/high block; waivers are
  explicit, documented, time-bounded).
- **CI permissions** — PR workflows hold `contents: read` only; the
  release workflow (`contents: write`) is reachable exclusively via
  maintainer tag pushes; action inputs reach scripts only through
  environment variables (no shell interpolation).
- **Release artifacts** — SHA-256 checksums produced, verified during
  the release process, and install scripts verify them before touching
  the filesystem (ADR-007).

### First scan results and fixes (OSV-Scanner, 2026-08-19, real output)

Initial scan over the committed lockfiles: 3 High, several Medium/Low.

| Finding | Severity | Fix |
|---|---|---|
| jackson-core 2.21.1 — GHSA-r7wm-3cxj-wff9 (StreamReadConstraints bypass, async parser only) | 8.7 High | lifted to 2.21.5 via a documented resolution rule (jackson is on the untrusted-OpenAPI parse path) |
| jackson-databind 2.21.1 — GHSA-j3rv-43j4-c7qm, GHSA-rmj7-2vxq-3g9f | 8.1 High | lifted to 2.21.5 (same rule) |
| jackson-databind 2.21.1 — five 5.3-6.5 Mediums | Medium | lifted to 2.21.5 |
| swagger-parser 2.1.40 transitives | — | upgraded to 2.1.44 (also cleared rhino 1.7.7.2) |
| logback-core 1.3.15 — GHSA-25qh-j22f-pwp8 (ktlint plugin's tool config, build-time only) | 5.9 Medium | lifted to 1.5.34 via resolution rule |
| log4j-api 2.26.0 — GHSA-qv9r-c865-cp47 (buildscript classpath via the ktlint plugin) | 6.3 Medium | lifted to 2.26.1 via a buildscript resolution rule |
| commons-lang3 3.17.0 — GHSA-j288-q9x7-2f5v (buildscript classpath) | 6.5 Medium | lifted to 3.18.0 via a buildscript resolution rule |

Post-fix scan: **clean** (exit 0). One explicit, time-bounded waiver in
`osv-scanner.toml` (surfaces as "Filtered 1 vulnerability"):

- kotlin-gradle-plugin 2.2.0 — GHSA-r937-wjx7-w2jp (6.7 Medium,
  build-time compiler plugin, never shipped); the only published fix is
  2.4.20-Beta1 — adopting a beta toolchain as a security reaction is
  not justified. Waiver expires 2027-02-19 (or earlier: bump when a
  stable Kotlin with the fix lands).

Resolution rules live in the root `build.gradle.kts` with per-rule
rationale comments; nothing was silently suppressed.

## Remaining limitations

- Jazzer coverage-guided fuzzing now runs as a bounded nightly job and
  was verified locally (19,537 runs / 121 s / 0 crashes on the OpenAPI
  target) — see docs/fuzzing.md.
- Symlink-specific handling and permission-edge testing (e.g. unreadable
  files on Windows) remain open follow-ups.
- The OSV scan and the CI jobs execute on the hosted runner once the
  repo hosting decision lands; the local equivalents have been run.
