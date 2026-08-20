# Research record — the canonical contract model and snapshot strategy

## Question

How should contract surfaces be represented so that **every input
format shares one diff engine, one classifier, and one mapper** — and
how should two points in time of a contract be captured for diffing?

## Research

- **Template matching precedent:** openapi-diff normalizes path
  parameter names when matching operations — the direct evidence for
  the canonical identity rule (`/users/{id}` and `/users/{userId}` are
  the same operation). Adopted as the model rule.
- **Format divergence evidence:** Buf and graphql-inspector show
  per-format breakage semantics differ; Confluent Schema Registry
  shows compatibility depends on producer/consumer direction. The
  model must not bake one format's semantics in — but the *structural*
  vocabulary (operations, schemas, enums, constraints, nullability) is
  shared across formats.
- **Snapshot design questions:** what identifies a capture (explicit
  versions vs database vs the git commit SHA), what makes captures
  reproducible, and how corruption is detected.

## Alternatives

- **Per-format models with per-format engines:** each format would
  need its own diff and its own rules — three rule sets that would
  inevitably disagree. Rejected.
- **A format-specific core model (OpenAPI-shaped):** rejected — would
  strand later formats.
- **Snapshot identity by explicit version identifiers:** rejected —
  extra bookkeeping with no demonstrated need; the git commit SHA is
  the natural key in the pre-merge workflow.
- **Database-backed snapshot history:** rejected — state and
  operations for no demonstrated need in a local-first CLI.

## Decision

ADR-001 + ADR-004: **one format-neutral canonical surface** (operations
with `(method, pathIdentity)` identity, parameters, request bodies,
responses, recursive schema nodes, locations) into which every format
maps via its own adapter — plus ADR-003's snapshot strategy:
file-backed, keyed by contract + git commit SHA, content-hash
verified, format-versioned, canonical-serialized.

## Why

One model means the diff engine, classifier, and mapper are written
once and reused by every format — a GraphQL field removal and a JSON
Schema property removal become the *same change kind* with the *same
classification*. Content-hash verification means a corrupted snapshot
can never be silently diffed; canonical serialization means captures
are byte-reproducible (determinism is a requirement, not a property).

## Implementation

- `ContractSurface` / `Operation` / `SchemaNode` (the model —
  [contract-model.md](../contract-model.md)).
- `SnapshotStore` — `<contract>@<sha>.snapshot.json`, atomic writes,
  index rebuilt by directory scan on every start, contract names
  sanitized ([architecture.md](../architecture.md)).
- Normalization at construction + the idempotent `canonical()`
  rebuild.

## Verification

- Canonicalization idempotence, deterministic serialization, and
  diff/canonical equivalence are property-pinned.
- Two independent captures of the same spec produce the identical
  content hash (real-CLI verification).
- Snapshot tampering, corrupt JSON, format-version refusal, index
  rebuild, and path escape are all regression-tested.

## Consequences

- Comparing non-git contexts needs an explicit `--sha` override
  (accepted trade-off).
- The model is a deliberate **intersection** of format concepts:
  per-format extras stay in adapters; omitted concepts (descriptions,
  examples, annotations) can only be added additively — and their
  absence is the redaction boundary.
- Identity is template-based, so path-template-variable renames are
  non-breaking and a real path change is a removal + addition — a
  consequence consumers of the tool must understand
  ([classification.md](../classification.md)).
