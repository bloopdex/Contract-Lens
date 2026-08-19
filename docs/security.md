# ContractLens security review (Phase 5)

Status: reviewed surfaces + findings below. This is NOT a claim of
Phase 5 "security hardening complete" beyond what is written here;
dependency scanning lands in CI with Phase 6, and the threat model
lives on the Phase 0 page.

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

## Remaining limitations
- No fuzzing via Jazzer yet (deferred to Phase 6 CI — see the Phase 5
  page fuzz note); the local seeded fuzz harness is the current
  evidence.
- Symlink-specific handling, permission-edge testing (e.g. unreadable
  files on Windows) and supply-chain scanning remain for Phase 6 CI.
