# Research record — the GraphQL and JSON Schema adapters

## Question

How do non-OpenAPI formats — GraphQL SDL schemas and JSON Schema
event contracts — fit the canonical model and the shared engine?

## Research

- **The deferral evidence (recorded at ADR-004):** the demonstrated
  use cases were OpenAPI; the mature parsing libraries and the
  pure-diff precedent tools are OpenAPI-centric; and per-format
  breakage semantics differ (Buf, graphql-inspector evidence) — so
  each format's parser and any format-specific rules belong to that
  format's own work, not the core.
- **GraphQL precedent:** graphql-inspector (breaking/dangerous/safe
  verdicts) is the ecosystem's GraphQL diff tool and its
  `considerUsage` rule shows usage-awareness needs usage data — the
  same conclusion the usage-graph research reached.
- **Parsing libraries:** graphql-java (26.0) provides a stable schema
  parser for SDL; JSON Schema needed nothing new — kotlinx JSON, a
  dependency already present, suffices for the core vocabulary.

## Alternatives

- **Format-specific engines per new format:** rejected — the whole
  point of the canonical model (ADR-001) is one engine.
- **Extending the core model per format:** rejected — the model stays
  an intersection; per-format extras stay in adapters.
- **Adapters on the shared engine:** chosen.

## Decision

ADR-004 amendment: `:graphql` and `:json-schema` adapters map their
formats into the canonical model; the shared diff engine and
classifier run unchanged. OpenAPI remains the primary format.

## Why

The revisit condition in ADR-004 fired (the multi-format work was on
the plan anyway), and the decisive evidence was that the shared engine
needed **no new rules** for the mapped surfaces — a GraphQL field
removal is the same `PROPERTY_REMOVED` kind as an OpenAPI one. Adding
adapters on the existing core was strictly cheaper and more
consistent than anything format-specific.

## Implementation

- `:graphql` (graphql-java schema parser): query/mutation fields →
  operations, args → parameters, types → schemas, NonNull →
  nullability, inline recursion → REF nodes. `.graphql`/`.graphqls`
  auto-detected by `snapshot` ([graphql.md](../graphql.md)).
- `:json-schema` (kotlinx JSON): core vocabulary → model; one event
  operation per document; local refs stay REF nodes; cross-document
  refs rejected ([json-schema.md](../json-schema.md)).

## Verification

- Adapter fixture suites for both formats.
- A GraphQL end-to-end classified diff (`NULLABLE_CHANGED`
  non-breaking + `REQUIRED_PROPERTY_ADDED` review) proved the shared
  engine and classifier ran unchanged.
- Both boundaries fuzzed (seeded harness + Jazzer targets) and
  benchmarked (`graphql-parse`, `json-schema-parse`).

## Consequences

- Both adapters are **groundwork-scope**: single-file SDL only; core
  JSON Schema vocabulary only (no composition keywords). What the
  adapters ignore produces no diff — a documented boundary
  ([limitations.md](../limitations.md)).
- No format-specific classification rules exist; if a format ever
  needs them, they belong in that format's adapter layer, per the
  original deferral reasoning.
