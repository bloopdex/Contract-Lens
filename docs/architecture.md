# ContractLens architecture

ContractLens turns contract documents into **canonical snapshots**,
diffs those snapshots **structurally**, classifies each change by its
**compatibility implications**, and maps the result onto **declared
consumers**. This page explains the architecture behind that pipeline —
what is shared across formats, what is adapter-specific, and why the
separation exists.

## The pipeline

```
contract document (OpenAPI / GraphQL SDL / JSON Schema)
        ↓  per-format parser adapter
canonical contract surface (format-neutral model)
        ↓  snapshot store
snapshot document (content-hash verified, keyed by contract + commit)
        ↓  structural diff engine
change set (26 structural kinds, precise locations, no verdicts)
        ↓  classifier
classified changes (breaking / non-breaking / review + semver labels)
        ↓  consumer mapper (optional, registry-backed)
per-consumer impact report
        ↓  reporter / JSON emission
human report · versioned JSON · DeployScore signal
```

Every stage is a pure function of its inputs except the parsers and the
file-backed store. Stages never know about the stages after them: the
diff engine does not know consumers exist, and the classifier does not
know which consumers read what.

## The canonical contract surface

The central design decision (ADR-001, ADR-004): every format maps into
**one** format-neutral model (`:core`), so the diff engine, classifier,
and mapper are written exactly once.

A `ContractSurface` contains:

| Concept | Representation |
|---|---|
| Operations | method (lowercase) + path template, plus parameters, request body, responses |
| Path identity | the path template with every `{param}` normalized to `{}` — `/users/{id}` and `/users/{userId}` are the **same operation** |
| Parameters | name, location (path/query/header/cookie), required, schema |
| Request body | content types → schema, required flag |
| Responses | normalized status → content types → schema |
| Schemas | recursive `SchemaNode`: types, format, properties (sorted), required list, items, enum values, nullability, constraints, default-presence, ref target |
| Locations | every node carries a logical location (`GET /users → response 200 → schema → properties.email`) and a source pointer for explainability |

**Why canonicalization exists:** without it, OpenAPI's vendor shapes,
GraphQL's type system, and JSON Schema's keyword vocabulary would each
need their own diff and their own compatibility rules — three rule sets
that would inevitably disagree. With it, a GraphQL field removal and a
JSON Schema property removal are the *same change kind* with the *same
classification*, and the reasoning is expressed in one vocabulary.

Deliberate omissions: descriptions, examples, and other prose are **not
captured** — they carry secrets and add nothing to compatibility
analysis (this is also the redaction boundary, pinned by tests).

## Snapshots

A snapshot (`:snapshot-store`, ADR-003) is a canonical JSON document:

```
contract + surface + identity (git-commit SHA) + capturedAt + content hash
```

The content hash covers everything except the timestamp; loading
re-verifies it, so a corrupted or hand-edited snapshot is refused
loudly and never diffed. Identical content always produces identical
bytes (test-pinned). Snapshots are stored as files keyed by
`<contract>@<commit-sha>.snapshot.json`; the store directory is scanned
to rebuild the index on every start, and contract names are sanitized
so they can never escape the directory.

## The structural diff engine

`DiffEngine.diff(oldSurface, newSurface)` produces a deterministic
change set (ADR-001 identity rules):

- operations match by `(method, path identity)`;
- parameters by `(name, location)`;
- responses by normalized status;
- schemas are traversed recursively, so a change deep inside a nested
  object surfaces as a leaf-level change with its full location.

The 26 change kinds are **structural facts** — `PROPERTY_REMOVED` says
a property disappeared, never whether that breaks anyone. Two kinds of
facts are deliberately conservative:

- **Renames are never inferred.** A removed field plus an added field
  are two independent facts; the classifier (not the engine) may flag
  same-type add/remove pairs as rename *candidates* for review.
- **Verdicts are null in the engine's output.** Compatibility is a
  separate concern (the classifier); the engine's JSON stays free of
  judgment.

## The classifier

A separate layer over the change set (ADR-001). Direction-aware rules
(what the consumer sends vs. what the provider sends) attach a verdict
and a deterministic reason to every change, and derive a semver label
from the verdict. The complete rule reference lives in
[classification.md](classification.md).

## Consumer impact mapping

`ConsumerMapper` (`:core`) joins the change set against the **declared
consumer registry** (ADR-002): a versioned, local-first YAML file
listing consumers (id, kind, contract, operation selectors). Selectors
use canonical operation identities, so `/users/{id}` and
`/users/{userId}` select the same operation; wildcards (`*`) select
all. Equivalent selectors dedupe deterministically; overlapping
selectors never produce duplicate impact records.

Honesty boundary: **"affected" means "declares consumption of the
changed surface"** — unregistered consumers are invisible to
ContractLens, and the report says so in every output. Mapping decides
*who might care*; the classifier decides *how bad it is*.

## Adapters

Each parser adapter maps one format into the canonical model and
enforces its own validation. What is shared: the model, the engine, the
classifier, the mapper, the reporters. What is adapter-specific: how a
format's syntax becomes that model.

| Adapter | Module | Scope |
|---|---|---|
| OpenAPI 3.0/3.1 | `:openapi-parser` | swagger-parser under the hood; local `$ref`s resolved with cycle/depth guards; remote/multi-file refs rejected; Swagger 2.0 rejected before conversion |
| GraphQL SDL | `:graphql` | single-file SDL via graphql-java's schema parser; query/mutation fields become operations, types become object schemas |
| JSON Schema | `:json-schema` | core vocabulary (type, properties, required, items, enum, constraints, nullability); local refs stay as ref nodes; cross-document refs rejected |
| Consumer registry | `:registry` | versioned YAML, kaml-decoded with strict validation (unknown fields fail) |
| Usage graph | `:registry` | same boundary and strictness; records which fields consumers read (see below) |
| Generated-client projection | `:generated-client` | not a parser — a projection of OpenAPI surfaces into generator-convention shape (ADR-006) |

Every adapter is bounded by `MAX_INPUT_BYTES` (10 MB) **before**
parsing and by its own nesting-depth guard; every failure is a typed
`ContractError` with a stable code — third-party exceptions never reach
the CLI.

## The usage graph (integration boundary)

The usage graph records which fields a consumer *actually reads*, per
operation and direction — the substrate for future usage-aware
classification. It is a validated, versioned format with typed errors,
canonical selectors, and deterministic merging of duplicate operations.

**It is not wired into classification.** Wiring it would require real
usage data — something recording which fields consumers read — and no
such producer exists. Classification therefore runs against the whole
contract, and the graph remains a documented integration boundary: when
a real usage producer appears, the classifier can be taught to weight
changes by actual reads.

## Module layout

Dependency direction is strictly inward — every adapter depends on
`:core` only, and `:cli` depends on all of them:

```
core             canonical model, error codes, diff, classifier, mapper, registry/usage domain, signal builder
openapi-parser   OpenAPI -> canonical model
snapshot-store   snapshot documents, file-backed store, integrity verification
registry         registry + usage-graph YAML -> validated domain (kaml)
generated-client OpenAPI surface -> generated-client projection (ADR-006)
graphql          GraphQL SDL -> canonical model
json-schema      JSON Schema event -> canonical model
cli              the contractlens executable (Clikt)
benchmark        deterministic performance scenarios (tool module)
fuzz             Jazzer coverage-guided fuzz targets (test-only module)
```

## Design principles

1. **Explainability over black-box scores** — every verdict carries its
   reason; every report shows the location.
2. **Determinism over ML** — identical inputs produce identical
   outputs, test- and fuzz-pinned.
3. **Conservative classification** — when the evidence is insufficient,
   the verdict is `review`, never a silent guess in either direction.
4. **Loud failures** — corrupt snapshots, malformed documents, and
   oversized inputs fail with typed errors, never silently degrade.
5. **Local-first** — file-backed storage, no network code path.
