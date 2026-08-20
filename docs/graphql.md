# GraphQL adapter

The `:graphql` adapter maps GraphQL SDL schemas into the canonical
model (ADR-004 amendment). `contractlens snapshot` auto-detects
`.graphql` / `.graphqls` files.

## What is captured

Via graphql-java's schema parser:

| SDL concept | Canonical model |
|---|---|
| query / mutation fields | operations (`method` = `query` / `mutation`) |
| field arguments | parameters (with their types as schemas) |
| object types | object schema nodes |
| fields of object types | properties |
| enums | enum nodes |
| built-in and custom scalars | scalar nodes |
| `NonNull` | nullability flags |
| recursive references (inline resolution) | `REF` nodes |

After mapping, the surface flows through the **unchanged** shared
engine and classifier: a GraphQL field removal is the same
`PROPERTY_REMOVED` kind, with the same direction-aware verdicts, as an
OpenAPI property removal. No format-specific rules were needed —
verified end-to-end by a classified GraphQL diff (`NULLABLE_CHANGED`
non-breaking, `REQUIRED_PROPERTY_ADDED` review, correct exit codes).

## Scope boundaries

- **Single-file SDL only.** Schema stitching and multi-file imports are
  not supported.
- **No directives, no introspection metadata, no descriptions** — they
  map to no canonical concept (the model's deliberate omission
  policy).
- **Response-centric by construction:** a GraphQL schema has no
  request-body notion, so the response-direction rules are the ones
  that apply; arguments surface as request-direction parameters.

## Verification

- Adapter fixture suite: SDL documents map into the canonical model
  with the expected operations, types, nullability, and refs.
- End-to-end: snapshot + `diff --classify` on a real SDL pair, correct
  verdicts and exit codes.
- Fuzzed at the parser boundary by both the seeded harness and a Jazzer
  target ([fuzzing.md](fuzzing.md)).
- Benchmark scenario `graphql-parse` (200 query fields, 200 types)
  tracks parse cost ([performance.md](performance.md)).

## CLI

```
contractlens snapshot schema.graphql --store .contractlens/snapshots   # format auto-detected
contractlens diff old.snapshot.json new.snapshot.json --classify       # unchanged pipeline
```
