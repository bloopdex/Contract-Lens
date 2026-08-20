# ADR-002 — Consumer strategy: explicit registry, not static discovery

- **Date:** 2026-08-19
- **Status:** Accepted
- **Related:** ADR-001 (identity rules the selectors reuse), ADR-005 (kaml as the YAML adapter), ADR-008 (the signal feed)

## Context

The tool's reason to exist is answering "which known consumers may be
affected" — pure schema diffing is solved elsewhere. Which consumers
exist is **not derivable from the contract document alone**: it must
come from explicit declaration (reliable) or from source analysis
(fragile, cross-language). This decision fixes how consumer knowledge
enters the tool.

## Decision

1. **Consumers are declared** in a versioned, local YAML registry
   (Backstage-shaped: `id`, `kind`, `contract`, operation `selectors`,
   `contact`, `notes` — see
   [impact-analysis.md](../impact-analysis.md)). The file is
   hand-edited, supplied per run via `--registry`, and validated
   strictly on ingest: unique ids, known kinds, canonical selectors
   (`*` or `METHOD /path-template`), unknown fields fail. There is no
   registry CRUD API and no registry storage — the file is the
   storage, the `:registry` module is the decode-and-validate adapter.

2. **"Affected" means "declares consumption of the changed surface."**
   Mapping: a change's location → owning contract + operation →
   registry entries whose selector matches the operation's canonical
   identity. Equivalent selectors dedupe deterministically; overlapping
   selectors never produce duplicate impacts.

3. **Honesty boundary.** Unregistered consumers are invisible to
   ContractLens, and every report says so. No confidence tiers until
   usage data exists.

## Alternatives considered

- **Static consumer discovery (source analysis)**: reliable ground
  truth in principle, but cross-language, fragile, high complexity, and
  it overlaps a sibling dependency-graph project's territory.
  Rejected for the core.
- **Both registry and discovery from day one**: complexity without a
  demonstrated need — the registry alone answers the question the tool
  was built for.
- **Pact-style consumer-driven contracts**: solves a different problem
  (a compatibility gate over authored consumer pacts) and requires
  every consumer to author pacts plus a broker service.

## Consequences

- False-negative boundary: unregistered consumers are invisible —
  stated in every report, never hidden.
- The registry is a maintenance burden on the declaring team — the
  accepted trade-off for reliable, explainable answers.
- Revisit when: a catalog import (the shape mirrors Backstage's
  `consumesApi` so an import is trivial) or usage-derived signals
  appear; or if the registry proves hard to maintain in practice.

## Verification

- 11 end-to-end impact fixtures (old/new/registry/expected through the
  real parser, engine, adapter, and mapper).
- 25 mapper/grammar unit tests (selection, wildcards, grouping,
  unmatched visibility, zero consumers).
- Property invariants (`ImpactPropertyTest`): determinism, registry
  ordering invariance, no phantom consumers, no phantom changes, no
  duplicate impacts, wildcard consumers receive every engine change.
- The dogfooding registry resolved a simulated change to both declared
  consumers in human and JSON output.
