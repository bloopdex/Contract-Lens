# Research record — the generated-client strategy

## Question

Consumers pin generated clients, so contract changes surface to them
as generated-code diffs. How should ContractLens diff generated
clients without taking on fragile cross-language parsing?

## Research

The ecosystem standard for this problem is **regenerate-and-diff**:
regenerate the client from the new spec and diff it against the
committed client in CI. Documented instances surveyed:

- OpenAPI Generator's own `ensure-up-to-date` mode;
- kubeflow/pipelines (client drift checks in CI);
- iris-ui's `check-api-types-drift.sh`.

All of them regenerate from the contract — none of them parse
generated output structurally. That is the pattern this tool was to
consume, not reinvent.

## Alternatives

- **Parse generated TypeScript/Java/Kotlin output:** three parsers'
  worth of maintenance for no new information — generated clients are
  derived from the contract. Cross-language fragility, and it
  duplicates the static-analysis territory already assigned elsewhere
  in the project's decisions. Rejected.
- **Byte-exact regeneration** (run OpenAPI Generator on both
  snapshots and diff): a heavy generator dependency, and the output is
  not deterministic across generator versions — a generator upgrade
  would produce diffs that mean nothing. Rejected.
- **Projection:** diff the source of truth **with generator
  knowledge** — deterministically project each snapshot into the
  client shape generators conventionally produce, then diff the
  projections with the shared engine. Chosen.

## Decision

ADR-006: `GeneratedClientProjection` derives method names from
method + path (`getUsersById`), merges parameters + body into request
objects, normalizes return types (`void` for empty responses), and
feeds the projection through the shared diff engine and classifier.
Generated source is never parsed.

## Why

The projection gives the consumer the answer they actually need
("what changed in the client surface I compile against") with the
same engine, rules, and explanations as every other format — while
staying deterministic and dependency-light. Both parsing and
regeneration trade correctness determinism for apparent fidelity; the
projection accepts a documented fidelity boundary instead.

## Implementation

- `:generated-client` module; `contractlens generated-diff --style
  ts|kotlin|java [--classify] [--json]`.
- Styles are recorded; TS/Kotlin/Java share conventions at this depth,
  so the style is reserved for generator-specific rules rather than
  divergent logic.

## Verification

- 4 fixture cases × 2 styles (TS + Kotlin) with expected outputs
  reviewed like code; Java conventions asserted in unit tests.
- Real-surface dogfooding found a projection crash on empty response
  content (the most common `@nestjs/swagger` output shape) — fixed
  (empty responses project a `void` return) and regression-tested.
- End-to-end classified `generated-diff` run with client-shaped
  locations and correct exit codes.

## Consequences

- **Convention-stable, not byte-exact** — the projection models what
  generators do; it does not claim to match any generator version
  ([limitations.md](../limitations.md)).
- **No model type names in reports:** `$ref`s resolve inline in the
  canonical model, so changes surface as substituted schemas, not
  "type X changed".
- Revisit condition recorded in ADR-006: generator-faithful output
  (operationId-based naming, model inventories) would *extend the
  projection* — generated output is still never parsed.
