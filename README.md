# ContractLens

Local-first API contract impact-analysis tool: detect contract changes,
classify them as breaking / non-breaking / review, and explain which
known consumers may be affected — before merge.

**Status:** Phase 2 (Diff Engine MVP) — structural diffing between two
snapshots is implemented. Classification verdicts (breaking / non-breaking
/ review) and the consumer registry are NOT implemented yet — the diff
engine reports structural facts only.

## What exists today (Phase 1)

- Canonical contract model (`:core`) — one format-neutral representation
  of a contract surface with source locations for explainability.
- OpenAPI 3.0/3.1 parser adapter (`:openapi-parser`) — swagger-parser is
  an internal detail; `$ref`s are resolved with cycle and depth guards;
  Swagger 2.0 and unknown versions are rejected before conversion.
- File-backed snapshot store (`:snapshot-store`) — snapshots keyed by
  contract + git commit SHA, content-hash verified, index rebuilt by
  directory scan on every start.
- CLI (`:cli`) — `contractlens snapshot | verify | list | diff` with
  structured JSON logs on stderr.

## Module layout

```
core            canonical model, normalization, errors (no IO, no CLI)
openapi-parser  OpenAPI -> canonical model adapter
snapshot-store  snapshot documents, file-backed store, git identity
cli             the contractlens executable (Clikt)
```

Dependency direction is strictly inward: `cli` -> `snapshot-store` -> `core`,
`cli` -> `openapi-parser` -> `core`.

## Build

Requires a JDK 17+ (CI runs JVM 17 and 21 on Linux and Windows).

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\..."   # or your JDK
.\gradlew.bat build          # compile + ktlintCheck + full test suite
.\gradlew.bat ktlintFormat   # auto-format
```

## Usage

```powershell
# capture a contract into a snapshot (commit SHA from git HEAD)
.\gradlew.bat :cli:run --args="snapshot api/openapi.yaml --store .contractlens/snapshots"

# or the installed script/distribution once packaged (Phase 6)
contractlens snapshot api/openapi.yaml
contractlens snapshot verify .contractlens/snapshots/users@<sha>.snapshot.json
contractlens snapshot list --store .contractlens/snapshots
contractlens diff old.snapshot.json new.snapshot.json
contractlens diff old.snapshot.json new.snapshot.json --json
```

Exit codes: `0` success, `1` reserved for breaking changes (the
classifier layer, a later phase), `2` operational errors (bad usage, bad
input, corrupt snapshots).

## `contractlens diff`

Diffs two verified snapshots (integrity is never bypassed) and prints
the deterministic structural change set:

```
old: users @ 0123...
new: users @ 4567...
changes: 3 (added 1, removed 1, changed 1)
  TYPE_CHANGED GET /users → response 200 → schema → items → properties.email : string → integer
```

`--json` emits a stable `contractlens-diff` v1 report with summary
counts and the full change list. The change taxonomy is documented in
`core/src/main/kotlin/dev/bloopdex/contractlens/core/diff/Change.kt` —
kinds are structural facts (operation/parameter/request/response/schema
levels, recursive property/array/constraint/enum/nullability diffs with
precise logical locations), and `verdict` is intentionally null until
the classifier layer exists. Renames are never inferred: a removed
field and an added field are two independent structural facts.

## Snapshot format (v1)

A snapshot is canonical JSON:

```json
{
  "formatVersion": 1,
  "contract": "users",
  "sourcePath": "/abs/path/openapi.yaml",
  "identity": {"kind": "git-commit", "sha": "<40-hex>"},
  "capturedAt": "<ISO-8601, variable metadata>",
  "contentHash": "<sha256 over the deterministic envelope>",
  "surface": { "name": "users", "kind": "openapi", "formatVersion": "3.0.3", "operations": [...] }
}
```

The content hash covers everything except `capturedAt`; modified or
corrupted snapshots are refused loudly and never trusted. Identical
content always produces identical bytes (determinism is pinned by tests).

## Known limitations (Phase 2)

- Local `$ref`s only; multi-file and remote references are rejected
  with `UNSUPPORTED_REFERENCE` (open question from Phase 0).
- Size/depth guards exist for nesting; full resource-limit hardening
  (anchor bombs, huge documents) is Phase 5.
- Classification verdicts, consumer mapping, and rename heuristics are
  deliberately absent — later phases.
- No remote configured for this repository yet — `.github/workflows/ci.yml`
  runs once the repo is pushed to a host.

## Documentation

The design record lives in the BloopLab Logseq graph (Phase 0: research,
canonical model, ruleset, ADR-001..005; Phase 1: this foundation).
