# The canonical contract model

Every input format — OpenAPI, GraphQL SDL, JSON Schema — is mapped by
its own parser adapter into **one** format-neutral representation, the
`ContractSurface` (ADR-001, ADR-004). Everything downstream (the diff
engine, the classifier, the consumer mapper, the reporters) consumes
only this model. This page documents each concept: what it represents,
why it exists, and how it participates in compatibility analysis.

## Why one model

Without canonicalization, OpenAPI's vendor shapes, GraphQL's type
system, and JSON Schema's keyword vocabulary would each need their own
diff and their own compatibility rules — three rule sets that would
inevitably disagree. With it, a GraphQL field removal and a JSON Schema
property removal are the *same change kind* with the *same
classification*, expressed in one vocabulary. The cost is per-format
mapping work in the adapters; the payoff is one diff engine, one
classifier, and one mapper for all formats.

The model is deliberately an **intersection**: concepts a format does
not have simply do not appear (e.g. GraphQL surfaces carry no content
types; OpenAPI surfaces carry no GraphQL directives). Adapters reject
or ignore what cannot be represented, loudly where it matters.

## The surface

```
ContractSurface
  name             the contract's logical name (CLI default: source file stem)
  kind             "openapi" | "graphql" | "json-schema"
  formatVersion    the source format's own version (e.g. "3.0.3")
  operations       sorted by (pathIdentity, method)
```

A surface is what you diff: two snapshots of the same contract, mapped
into this shape, produce a change set over these concepts.

## Operations and canonical identity

| Field | What it represents |
|---|---|
| `method` | lowercase HTTP method (GraphQL: the field's operation kind — query/mutation) |
| `path` | the path template exactly as written in the source |
| `pathIdentity` | the template with every `{param}` normalized to `{}` |
| `parameters`, `requestBody`, `responses` | the operation's contract surface |
| `location` | source pointer, e.g. `paths./users/{id}.get` |

**Identity is the template, not its spelling.** `/users/{id}` and
`/users/{userId}` are the same operation: both normalize to
`/users/{}`. This is the identity rule that makes template-variable
renames non-breaking (ADR-001), and every stage — diff matching,
registry selectors, usage-graph selectors, impact mapping — reuses it.
A real path change (`/users/{}` → `/users/{}/orders`) is therefore an
operation removal plus an addition, not a rename.

## Parameters

`name`, `in` (path / query / header / cookie), `required`, `schema`.
Parameters are matched by `(in, name)` across old and new surfaces;
inherited path-item parameters are flattened into each operation.

Why parameters exist as first-class concepts: their compatibility rules
differ from body properties (a removed *parameter* is breaking; a
removed *request property* is review — provider validation of unknown
fields is provider-dependent, while unknown parameters are usually
rejected).

## Request bodies and responses

- `RequestBody`: `required` + a content-type → schema map.
- `Response`: a content-type → schema map, keyed by **normalized
  status** (`"200"`, `"2xx"`, `"default"`).

Content types are part of identity: adding or removing one is its own
change kind, because consumers negotiate representations explicitly.
Bodies and responses carry *direction* implicitly (request / response),
which the classifier's location grammar reads.

## Schema nodes

`SchemaNode` is the recursive heart of the model:

| Field | Meaning |
|---|---|
| `nodeType` | `SCALAR` / `OBJECT` / `ARRAY` / `ENUM` / `REF` / `ANY` |
| `types` | non-null JSON types, sorted; empty = unspecified |
| `format` | the source's format hint (e.g. `int64`) |
| `properties` | property name → schema, sorted by name |
| `required` | sorted, deduped; every entry names a key in `properties` |
| `items` | array element schema |
| `enumValues` | stringified values in document order |
| `nullable` | normalized from OpenAPI 3.0's `nullable: true` **and** 3.1's `"null"` in `type` |
| `refTarget` | the reference pointer, e.g. `#/components/schemas/User` |
| `constraints` | minimum/maximum, min/maxLength, pattern, min/maxItems |
| `defaultPresent` | whether the source declares a default (a classification input) |
| `location` | the source pointer chain |

Every property of a schema node participates in compatibility analysis:
types feed `TYPE_CHANGED`; the required list feeds the
required-flip kinds; enum values feed `ENUM_CHANGED`; constraints feed
`CONSTRAINT_CHANGED`; nullability feeds `NULLABLE_CHANGED`; the ref
target feeds `REF_TARGET_CHANGED`. The exact rules are in
[classification.md](classification.md).

## References

Local `$ref`s are **resolved for comparison** and the target name is
**preserved for explanation** (`refTarget`): two schemas that both
reference `#/components/schemas/User` are compared by their resolved
content, and a change in a referenced schema is reported at *every*
referencing location. Remote and multi-file references are rejected
(`UNSUPPORTED_REFERENCE`) — the parser boundary. Recursive references
are cut at a 64-level depth guard (`DEPTH_EXCEEDED`), which doubles as
the cycle termination.

## Locations

Every node carries a `location` — a document-pointer-style path back
into the source contract, e.g.

```
paths./users/{id}.get.responses.200.content.application/json.schema.properties.email
```

Locations exist for **explainability**: reports quote the schema path,
never an internal id. They are also the substrate of direction
detection (the classifier reads `→ request body` / `→ parameter` /
`→ response` from the location grammar) and of consumer mapping (the
mapper derives the affected operation from the location grammar).

## Normalization invariants

At construction and via the defensive `canonical()` rebuild:

- properties and responses are sorted (by name / status), parameters by
  `(in, name)`, operations by `(pathIdentity, method)`;
- the required list is sorted and deduped;
- types are sorted;
- `canonical(canonical(x)) == canonical(x)` — idempotence, pinned by
  property tests;
- canonical serialization is deterministic: byte-identical inputs
  produce byte-identical snapshots, diffs, and reports.

## Deliberate omissions

Descriptions, examples, deprecation flags, security schemes, servers,
and other prose are **not captured**. They carry no structural
compatibility information, they are where secrets live, and their
absence is the redaction boundary: snapshots and reports cannot leak
what the model never holds (pinned by a regression test). Omitted
concepts can be added additively if a future need is demonstrated —
the model version gates compatibility.
