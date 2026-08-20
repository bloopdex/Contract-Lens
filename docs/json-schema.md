# JSON Schema adapter

The `:json-schema` adapter maps JSON Schema event contracts into the
canonical model (ADR-004 amendment). Invoke with
`contractlens snapshot <file> --format json-schema`.

## What is captured

The adapter is a direct mapping of the core vocabulary (built on
kotlinx JSON — no new dependencies):

| JSON Schema keyword | Canonical model |
|---|---|
| `type` | types (3.1-style `"null"` in `type` included) |
| `properties` | object properties |
| `required` | the required list |
| `enum` | enum values |
| `items` | array items |
| `min`/`max`, `minLength`/`maxLength`, `pattern`, `minItems`/`maxItems` | constraints |
| `nullable` (OpenAPI-style) | nullability |
| `default` | default-presence (a classification input) |

Each document becomes **one event operation**: the schema is the
operation's response body, so response-direction rules apply. After
mapping, the surface flows through the unchanged shared engine and
classifier.

## Scope boundaries

- **Local refs stay `REF` nodes** (resolved for comparison, name
  preserved for explanation — the standard model behavior);
  **cross-document refs are rejected loudly** (`UNSUPPORTED_REFERENCE`).
- **Core vocabulary only**: composition keywords (`allOf`/`anyOf`/
  `oneOf`), `$defs`-spanning behavior beyond what the adapter maps,
  and annotations beyond `default` are out of scope today. Anything
  outside the captured vocabulary is ignored by the adapter, so a
  change there produces no diff — a boundary documented here rather
  than implied.

## Verification

- Adapter fixture suite: event documents map into the canonical model
  with expected types, required, enums, items, constraints, and
  nullability.
- Snapshot + diff end-to-end via the shared pipeline.
- Fuzzed at the parser boundary by both the seeded harness and a Jazzer
  target ([fuzzing.md](fuzzing.md)).
- Benchmark scenario `json-schema-parse` (1,000 properties) tracks
  parse cost ([performance.md](performance.md)).

## CLI

```
contractlens snapshot event.json --format json-schema --store .contractlens/snapshots
contractlens diff old.snapshot.json new.snapshot.json --classify
```
