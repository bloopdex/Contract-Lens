# ADR-004 — Format scope: OpenAPI 3.0/3.1 for the MVP, canonical model designed format-neutral

- **Date:** 2026-08-19
- **Status:** Accepted (amended — see below)
- **Related:** ADR-001 (the model this constrains), ADR-005 (swagger-parser)

## Context

The contract ecosystem spans OpenAPI, GraphQL SDL, JSON Schema events,
protobuf, and more — with per-format breakage semantics that differ
(the recorded Buf and graphql-inspector evidence: the same structural
change means different things per format and per consumer type). The
demonstrated use cases were OpenAPI documents, and the mature parsing
libraries and pure-diff precedent tools are OpenAPI-centric. Question:
which format does the first usable tool support, and how is the model
designed so later formats fit without rework?

## Decision

1. **OpenAPI 3.0/3.1 only for the MVP.** Swagger 2.0 and other
   versions are rejected with a typed error *before* conversion (the
   parsing library would otherwise silently convert them).

2. **The canonical model is designed format-neutral** — operations,
   schemas, enums, constraints, nullability — so GraphQL SDL and JSON
   Schema can map in later through their own parser adapters, feeding
   the same diff engine and classifier.

3. **GraphQL SDL and JSON Schema event contracts are explicitly
   deferred** with their own parser + rules work.

## Alternatives considered

- **All three formats from day one**: rejected — the demonstrated use
  cases were OpenAPI, and building adapters before the core was proven
  would dilute it.
- **An OpenAPI-shaped core model (no neutrality)**: rejected — would
  strand later formats and force per-format diffing (ADR-001).

## Consequences

- No GraphQL or JSON Schema story at launch — documented limitation
  until the amendment below.
- The canonical model necessarily becomes the intersection of the
  supported formats' concepts; per-format extras stay in the adapters.
- Revisit when: a real GraphQL contract or event contract appears in
  the tool's targets.

## Amendment (2026-08-19) — GraphQL and JSON Schema adapters added

The revisit condition fired during development: the generated-client
and multi-format work landed the `:graphql` and `:json-schema` adapters
on the shared engine. The core needed **no new rules** — the structural
kinds and the classifier ran unchanged on the mapped surfaces (verified
by a GraphQL end-to-end classified diff). The deferral was removed by
implementing the adapters, not by re-deciding the model: the model
stays format-neutral and the adapters stay per-format.

## Verification

- OpenAPI 3.0 and 3.1 fixtures parse into snapshots; Swagger 2.0 and
  unknown versions fail with `UNSUPPORTED_VERSION` before parsing.
- Adapter fixtures: GraphQL SDL and JSON Schema documents map into the
  canonical model and diff/classify end-to-end (see the amendment).
