# Generated-client projection diffing

Consumers often pin generated clients, so a provider contract change
surfaces to them as a generated-code diff. `contractlens generated-diff`
covers that path — without ever parsing generated source (ADR-006).

## Why projection instead of parsing

The obvious approach — diff the generated files — was rejected for two
recorded reasons:

- **Cross-language fragility.** Parsing generated TypeScript, Java, and
  Kotlin output is three parsers' worth of maintenance for no new
  information: generated clients are *derived* from the contract.
- **Version noise.** Byte-exact comparison depends on the generator
  version; a generator upgrade would produce diffs that mean nothing.

The alternative adopted: **project the contract the way a generator
would**, deterministically, and diff the projections with the shared
engine.

## What the projection produces

`GeneratedClientProjection.project(surface, style)` turns each
operation into the client shape OpenAPI-generator conventions produce:

- **method names** from method + path: `GET /users/{id}` →
  `getUsersById`;
- **merged request objects**: parameters + request body combined into
  one request schema per operation;
- **normalized return types**: the success response schema, or a `void`
  return when the response carries no content (a real-surface
  dogfooding crash on empty response content was found and fixed this
  way);
- **styles**: `ts`, `kotlin`, `java` — the three share naming
  conventions at this depth; the style is recorded and reserved for
  generator-specific rules.

The projected surface is then diffed by the same engine — and
optionally classified — as any other surface. Locations in the report
read client-shaped:

```
POST client.postSessions → request body → schema → properties.body → properties.webhookUrl
```

## Honest boundaries

- **Convention-stable, not byte-exact.** The projection models what
  generators conventionally do; it does not claim to match any specific
  generator version's output.
- **No model type names.** `$ref`s resolve inline in the canonical
  model, so the projection reports method-level and inline schema
  changes; a `#/components/schemas/User` change surfaces as its
  substituted schema, not as "type User changed".
- **The projection is a model of generators, not a generator.** It
  consumes OpenAPI snapshots only.

These boundaries are deliberate (ADR-006 trade-offs), and the revisit
condition is recorded: if generator-faithful output (operationId-based
naming, model inventories) ever has a demonstrated need, the projection
is extended — generated output is still never parsed.

## CLI

```
contractlens generated-diff <old> <new> --style ts|kotlin|java [--classify] [--json]
```

`--classify` adds verdicts and exit 1 on breaking; `--json` emits
`contractlens-generated-diff` v1. Full reference:
[cli.md](cli.md), [output-formats.md](output-formats.md).

## Verification

- 4 fixture cases × 2 styles (TS + Kotlin), expected files reviewed
  like code; Java conventions asserted in unit tests.
- Property-invariant coverage via the shared engine's pinned
  invariants.
- The empty-response-content crash is regression-tested.
