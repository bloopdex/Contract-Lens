# Research record — the usage graph and the deferral of usage-aware classification

## Question

Should classification use **which fields consumers actually read** —
and if so, when?

## Research

- **The ecosystem precedent:** graphql-inspector's
  `--rule considerUsage` reclassifies GraphQL changes against real
  schema usage data. It exists precisely because that ecosystem
  *has* usage data — an important boundary condition: the precedent
  shows the endgame, and shows what it depends on.
- **The consumer-kind precedent:** Buf's severity categories
  (FILE/PACKAGE/WIRE_JSON/WIRE) encode "the same change means
  different things to different consumer kinds" — the direction the
  per-consumer re-ranking would eventually take.
- **The actual data situation (checked, not assumed):** nothing
  records which fields consumers read. No instrumentation exists
  anywhere in the surrounding projects, and the usage-graph format
  has no producers.

## Alternatives

- **Wire usage-aware classification now, against an empty dataset:**
  rejected — it would be an untested heuristic whose outputs depend on
  data that does not exist; a ruleset whose behavior changes with an
  empty input is not a deterministic, explainable ruleset.
- **Drop the usage-graph idea entirely:** rejected — the ecosystem
  evidence shows it is the endgame, and building the format now makes
  the eventual wiring an integration step.
- **Build the format and validation now; defer the wiring until a
  real producer exists:** chosen.

## Decision

The usage graph ships as a **validated, versioned format** (per
(consumer, contract) records, canonical operation selectors, dotted
field paths per direction, deterministic merging) and is
**deliberately not wired into classification or mapping**. The
deferral is recorded with its evidence: no real usage producers
exist, and wiring against an empty dataset would violate the
deterministic ruleset contract (ADR-001).

## Why

A deferral with a reason is a decision: the format without the wiring
keeps the substrate ready and the boundary honest; the wiring without
the data would fake an accuracy the tool does not have. Classification
against the whole contract is the correct, explainable behavior until
field-level evidence exists.

## Implementation

`core/usage/Usage.kt` + the `:registry` adapter: versioned YAML,
strict validation (`USAGE_*` typed errors), canonical selectors
shared with the registry, dotted-path validation, deterministic
merge of duplicate operations, refusal of duplicate
(consumer, contract) records — see [usage-graph.md](../usage-graph.md).

## Verification

- Format + validation unit tests; fuzzed at the boundary
  (`fuzzUsageGraph` Jazzer target and the seeded harness).

## Consequences

- The integration boundary is documented in three layers: the concept
  doc, the architecture doc, and ADR-001's revisit condition
  ("when real usage data exists, verdicts may be re-ranked per
  consumer").
- Until a producer appears, every report classifies against the whole
  contract — which may overstate impact for consumers that read only
  a subset. That is the documented trade-off of the deferral, not a
  hidden gap.
