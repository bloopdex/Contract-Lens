# Security review

Contracts and registries are **untrusted input** — they come from
other teams' tools, CI artifacts, and the internet. This page maps
each threat to its boundary, its defense, and its verification
evidence. Claims are test-pinned or they are not made: nothing here is
"secure by hope". The vulnerability policy (severities, waivers,
reporting) lives in [SECURITY.md](../SECURITY.md).

## Threat → boundary → defense → verification

### Malformed or hostile documents (YAML/JSON)

- **Threat:** anchor bombs ("billion laughs"), alias expansion, deeply
  nested schemas, recursive `$ref` cycles, multi-megabyte documents —
  the classic YAML attack surface.
- **Boundary:** every text input — OpenAPI, GraphQL SDL, JSON Schema,
  registry, usage graph.
- **Defense:** size and depth limits **before** parsing
  (`MAX_INPUT_BYTES` = 10 MB → `INPUT_TOO_LARGE`; 64-level depth guards
  → `DEPTH_EXCEEDED`, which doubles as cycle termination); the OpenAPI
  raw-document check uses a safe YAML constructor and refuses duplicate
  keys; every parser failure is a typed `ContractError` with a stable
  code — third-party exceptions never reach the CLI.
- **Verification:** regression-tested at every boundary; fuzzed by the
  seeded harness (recorded 1,000,000 parser executions, clean) and six
  Jazzer coverage-guided targets (2,573,262 recorded executions, 0
  crashes) — [fuzzing.md](fuzzing.md).

### Path traversal and filesystem behavior

- **Threat:** a contract name or file argument shaped like a path
  escaping the snapshot store or reading arbitrary files.
- **Boundary:** snapshot store filenames; CLI file arguments.
- **Defense:** contract names sanitized before becoming filenames; CLI
  file arguments must exist, be regular files, and be readable; all
  paths normalized before use.
- **Verification:** regression test pins that a contract name like
  `../evil` cannot escape the store directory.

### Secret leakage

- **Threat:** descriptions, examples, and other spec prose carry
  secrets; they must never reach snapshots, reports, or logs.
- **Boundary:** the canonical model — by **construction**.
- **Defense:** the model never captures descriptions, examples, or
  prose, so nothing downstream can leak them. No sanitization pass
  exists because there is nothing to sanitize — the content was never
  admitted.
- **Verification:** regression test pins that secrets placed in
  descriptions/examples do not appear in snapshot bytes or in
  diff/impact reports. The signal feed re-pins the tighter payload
  boundary (metadata only).

### Reference resolution

- **Threat:** remote references pulling content from attacker
  endpoints; multi-file sprawl hiding content from review.
- **Boundary:** all `$ref` resolution.
- **Defense:** local references only; remote and multi-file references
  are rejected (`UNSUPPORTED_REFERENCE`). No network code path exists
  in the tool. JSON Schema cross-document refs are rejected loudly.
- **Verification:** fixture + failure tests across the adapters; the
  zero-network property is structural — there is no network client in
  the codebase at all.

### Snapshot integrity

- **Threat:** corrupted or hand-edited snapshots silently compared,
  producing wrong verdicts.
- **Boundary:** every snapshot load.
- **Defense:** content hash over everything except the timestamp,
  re-verified on every load; mismatch → `SNAPSHOT_INTEGRITY`, loud
  refusal, never a silent diff.
- **Verification:** SnapshotStoreTest + real-CLI tamper tests; no
  command bypasses the check.

### The registry and usage graph

- **Threat:** a malformed or crafted registry steering impact mapping.
- **Boundary:** the `:registry` adapter.
- **Defense:** kaml strict-mode decode — unknown fields fail, never
  silently reinterpreted; duplicate ids, unknown kinds, malformed
  selectors all fail with typed errors.
- **Verification:** RegistryParserTest failure suite; fuzzed at the
  boundary.

### Dependencies and supply chain

- **Threat:** vulnerable or tampered dependencies.
- **Boundary:** the Gradle resolution graph; release artifacts.
- **Defense:** SHA-256 verification metadata
  (`--dependency-verification=strict` in CI), dependency locking
  (committed lockfiles per module), OSV-Scanner with the severity
  policy in SECURITY.md, SHA-256-checksummed release artifacts
  (ADR-007).
- **Verification:** the first scan found real issues and they were
  fixed — see below.

## Dependency review and the first OSV scan

The manual review (August 2026) spot-checked the two most
attack-relevant dependencies:

- `snakeyaml` 2.4 — no known direct vulnerabilities (published CVEs
  predate 2.0).
- `swagger-parser` — upgraded **2.1.27 → 2.1.40** for
  GHSA-2237-hv52-mmg9 (High: a thread-safety race in OpenAPI 3.1
  parsing, affected 2.1.15-2.1.38). ContractLens parses
  single-threaded, but the patched version removes the exposure
  entirely. Later upgraded again **2.1.40 → 2.1.44** by the systematic
  scan (which also cleared the rhino 1.7.7.2 transitive).

The first OSV-Scanner run over the committed lockfiles then found
further findings, all fixed with documented resolution rules (root
`build.gradle.kts`, per-rule rationale comments — nothing silently
suppressed):

| Finding | Severity | Fix |
|---|---|---|
| jackson-core 2.21.1 — GHSA-r7wm-3cxj-wff9 (StreamReadConstraints bypass, async parser only) | 8.7 High | lifted to 2.21.5 (jackson sits on the untrusted-OpenAPI parse path) |
| jackson-databind 2.21.1 — GHSA-j3rv-43j4-c7qm, GHSA-rmj7-2vxq-3g9f | 8.1 High | lifted to 2.21.5 (same rule) |
| jackson-databind 2.21.1 — five 5.3-6.5 Mediums | Medium | lifted to 2.21.5 |
| swagger-parser 2.1.40 transitives | — | upgraded to 2.1.44 (cleared rhino 1.7.7.2) |
| logback-core 1.3.15 — GHSA-25qh-j22f-pwp8 (ktlint plugin tool config, build-time only) | 5.9 Medium | lifted to 1.5.34 |
| log4j-api 2.26.0 — GHSA-qv9r-c865-cp47 (buildscript classpath) | 6.3 Medium | lifted to 2.26.1 |
| commons-lang3 3.17.0 — GHSA-j288-q9x7-2f5v (buildscript classpath) | 6.5 Medium | lifted to 3.18.0 |

Post-fix scan: **clean (exit 0)**. One explicit, time-bounded waiver in
`osv-scanner.toml`: kotlin-gradle-plugin 2.2.0 (GHSA-r937-wjx7-w2jp,
6.7 Medium, build-time only; the only published fix is a beta — adopting
a beta toolchain as a security reaction is not justified). Waiver
expires 2027-02-19, or earlier when a stable Kotlin with the fix
ships. The seeded fuzz sweeps were **re-run clean after the dependency
upgrades** (1M parser executions + 200k classifier iterations).

## Automation (the standing layer)

- **Dependency verification** — SHA-256 metadata, strict resolution in
  CI; regenerated only after a deliberate, reviewed upgrade.
- **Dependency locking** — committed lockfiles; Dependabot takes over
  updates once hosted.
- **Vulnerability scanning** — OSV-Scanner in CI over the committed
  lockfiles; failure policy per severity in SECURITY.md.
- **CI permissions** — PR workflows `contents: read` only; the release
  workflow (`contents: write`) runs exclusively on maintainer tag
  pushes; Action inputs reach scripts only via environment variables
  (no shell interpolation of untrusted input).
- **Release artifacts** — SHA-256 checksums produced, verified during
  release, and re-verified by the install scripts before they touch
  the filesystem (ADR-007).

## Remaining limitations

- Symlink-specific handling and permission-edge testing (e.g.
  unreadable files on Windows) remain open follow-ups.
- The OSV scan and CI jobs execute on the hosted runner once the
  repository hosting decision lands; the local equivalents have been
  run and recorded.
- `review` verdicts shift some security-adjacent judgment (e.g. request
  property removal) to humans — by design, and documented in
  [classification.md](classification.md).
