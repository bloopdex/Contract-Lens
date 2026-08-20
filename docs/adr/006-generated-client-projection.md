# ADR-006 — Generated-client diffing: deterministic projection, never parsing generated output

- **Date:** 2026-08-19
- **Status:** Accepted
- **Related:** ADR-001 (identity + ruleset reused), ADR-002 (the `generated-client` consumer kind)
- **Numbering note:** the delivery notes planned this decision as
  "ADR-002"; ADR-002 was already the consumer strategy. The record
  keeps **ADR-006** — the collision is documented, not silently
  renumbered.

## Context

Consumers often pin generated clients, so provider contract changes
surface to them as generated-code diffs (an ecosystem-standard
regenerate-and-diff pattern). The question: what should ContractLens
diff — the generated output files, or something upstream of them?

## Decision

**Diff the source of truth with generator knowledge.** A deterministic
projection of OpenAPI snapshots into generated-client shape — client
method names derived from method + path (`getUsersById` from
`GET /users/{id}`), merged request objects (parameters + body),
normalized return types, `void` returns — diffed by the shared engine
and optionally classified. Generated client **source is never parsed**.

## Alternatives considered

- **Parse generated TypeScript/Java/Kotlin output**: rejected —
  cross-language parsing is fragile and duplicates the static-analysis
  territory ADR-002 assigned elsewhere (its approach-C reasoning
  applies).
- **Byte-exact regeneration** (run OpenAPI Generator and compare):
  rejected — a heavy generator dependency and version noise; the
  output is not deterministic across generator versions.

## Consequences

- The projection is **convention-stable, not byte-exact** against any
  generator version — a documented limitation.
- `$ref`s resolve inline in the canonical model, so model *type names*
  are not reported: method-level and inline schema changes only.
- TS/Kotlin/Java share naming conventions at this depth; the `--style`
  is recorded and reserved for generator-specific rules.
- Revisit when: generator-faithful output (operationId-based naming,
  model inventories) has a demonstrated need — extend the projection,
  never parse output.

## Verification

- `GeneratedFixtureTest`: 4 cases × 2 styles (TS + Kotlin), expected
  files reviewed like code; Java conventions asserted in unit tests.
- Real-surface dogfooding found and fixed a projection crash on empty
  response content (regression-tested); responses without content now
  contribute a `void` return schema.
- `contractlens generated-diff --style ts --classify` verified
  end-to-end with client-shaped locations and correct exit codes.
