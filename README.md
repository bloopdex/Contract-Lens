# ContractLens

Local-first API contract impact-analysis tool: detect contract changes,
classify them as breaking / non-breaking / review, and explain which
known consumers may be affected — before merge.

**Status:** Phase 3 (Consumer Registry & Impact Mapping) — structural
diffing between two snapshots plus explicit consumer-registry impact
mapping are implemented. Classification verdicts (breaking / non-breaking
/ review) are NOT implemented yet — the diff engine reports structural
facts only, and the mapper reports declared-consumer matches only.

## What exists today (Phase 3)

- Canonical contract model (`:core`) — one format-neutral representation
  of a contract surface with source locations for explainability.
- OpenAPI 3.0/3.1 parser adapter (`:openapi-parser`) — swagger-parser is
  an internal detail; `$ref`s are resolved with cycle and depth guards;
  Swagger 2.0 and unknown versions are rejected before conversion.
- File-backed snapshot store (`:snapshot-store`) — snapshots keyed by
  contract + git commit SHA, content-hash verified, index rebuilt by
  directory scan on every start.
- Consumer registry (`:core` + `:registry`) — versioned YAML registry
  (Backstage-shaped, ADR-002), kaml-decoded with strict validation, and
  a pure consumer mapper producing deterministic per-consumer impacts.
- CLI (`:cli`) — `contractlens snapshot | verify | list | diff | impact`
  with structured JSON logs on stderr.

## Module layout

```
core            canonical model, registry domain, pure mapper, errors
openapi-parser  OpenAPI -> canonical model adapter
snapshot-store  snapshot documents, file-backed store, git identity
registry        registry YAML -> validated domain (kaml)
cli             the contractlens executable (Clikt)
```

Dependency direction is strictly inward: `cli` -> `snapshot-store` ->
`core`, `cli` -> `openapi-parser` -> `core`, `cli` -> `registry` -> `core`.

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
contractlens impact old.snapshot.json new.snapshot.json --registry registry.yaml
contractlens impact old.snapshot.json new.snapshot.json --registry registry.yaml --json
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

## `contractlens impact`

Diffs two verified snapshots of the same contract, loads the consumer
registry, and maps every structural change to the registered consumers
that declare consumption of the affected operation:

```
contract: thorn-api
changes: 3
registered consumers: 2
affected consumers: 1
mapped changes: 2

consumer thornwa-frontend (frontend)
  GET /users/{id}
    PROPERTY_REMOVED GET /users/{id} → response 200 → schema → properties.email
    reason: consumer declares this operation

unmapped changes: 2
  OPERATION_REMOVED GET /audit
  (no registered consumer declares these operations)
note: unregistered consumers are not visible to ContractLens.
```

`--json` emits a stable `contractlens-impact` v1 report: summary counts
(changes, affected consumers, mapped change-consumer associations), the
full change set (unmatched changes stay visible), and per-consumer
impacts preserving each change and its mapping reason.

### Consumer registry (v1)

Explicit, versioned, local-first YAML (ADR-002). See
`docs/examples/thornwa-registry.yaml` for a real dogfooding registry:

```yaml
version: 1
consumers:
  - id: thornwa-frontend          # stable identity — must be unique
    kind: frontend                # frontend | service | sdk | generated-client | integration
    contract: thorn-api           # the snapshot contract name
    operations:                   # "*" (all) or METHOD + path-template
      - GET /users/{id}
    contact: frontend team        # optional
    notes: optional free-form     # optional
```

Validation is strict and deterministic: unsupported versions
(`REGISTRY_VERSION_UNSUPPORTED`), duplicate ids (`REGISTRY_DUPLICATE_ID`),
invalid selectors (`REGISTRY_SELECTOR_INVALID`), and unknown fields
(kaml strict mode) all fail with typed errors — never silently
reinterpreted. Selectors use the canonical operation identity
(lowercase method + normalized path template), so `/users/{id}` and
`/users/{userId}` are the same operation. Equivalent selectors dedupe
deterministically; overlapping selectors never produce duplicate impact
records.

Honesty boundary (ADR-002): registered consumers are the DECLARED
knowledge — unregistered consumers are invisible to ContractLens, and
"affected" means "declares consumption of the changed surface", never
"will definitely break" (the classifier decides breakage later).

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

## Known limitations (Phase 3)

- Local `$ref`s only; multi-file and remote references are rejected
  with `UNSUPPORTED_REFERENCE` (open question from Phase 0).
- Size/depth guards exist for nesting; full resource-limit hardening
  (anchor bombs, huge documents) is Phase 5.
- Classification verdicts and rename heuristics are deliberately
  absent — the classifier is the next layer.
- Static source discovery does not exist and is out of scope for the
  core (ADR-002): consumers are only what the registry declares.
- `impact` requires both snapshots to be the same contract name
  (`CONTRACT_MISMATCH` otherwise); contract renames are not mapped.
- Strict OpenAPI validation can refuse real-world dumps with undeclared
  path parameters (found on thorn-api's NestJS dump in dogfooding) —
  that is the correct loud failure; the fix belongs to the spec source.
- No remote configured for this repository yet — `.github/workflows/ci.yml`
  runs once the repo is pushed to a host.

## Documentation

The design record lives in the BloopLab Logseq graph (Phase 0: research,
canonical model, ruleset, ADR-001..005; Phase 1: this foundation).
