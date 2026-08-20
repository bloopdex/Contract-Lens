# Research record — contract compatibility: the gap and the approaches

## Question

Is there a technically meaningful gap between existing contract-diff
tools and the problem of explaining **which real consumers may be
affected** by a contract change — and if so, what should the tool be?

## Research

The hypothesis: contract changes are easy to make but hard to reason
about, because each consumer compiles against its own copy of the
contract, so provider changes break consumers at runtime, not build
time. The canonical documented case (from the Optic retrospective
literature): a single field rename passed review, passed tests, and
took down point-of-sale systems across 19 restaurants.

What a developer needs after a change, in order: (1) what changed
structurally, (2) whether it breaks consumers, (3) which known
consumers it hits, (4) why — with the exact schema path. Detection is
necessary but not sufficient; the consumer question is the harder
problem.

Ten existing solutions across six categories were surveyed (tool
documentation + release/landscape data, August 2026):

- **oasdiff** (Go, active): the mature pure-diff leader — `diff` /
  `breaking` / `changelog`, ERR vs WARN severities, 300+ rules,
  `x-stability-level` gates, ignore lists. **No consumer mapping.**
- **openapi-diff** (OpenAPITools): CLI + Maven plugin,
  `--fail-on-incompatible`; path-param-name normalization (evidence
  adopted for the canonical identity rule). **No consumer mapping.**
- **Optic** (archived January 2026 — Atlassian acquisition): its
  shutdown left a hole in API-change tooling; it never had consumer
  impact either.
- **Pact / PactFlow bi-directional testing**: consumer-driven;
  statically cross-checks consumer pacts against the published spec —
  the closest existing thing, but it requires every consumer to author
  pacts, a broker service, and provider-side adoption; it is a
  compatibility *gate*, not a pre-merge *explainer*.
- **graphql-inspector** (GraphQL): breaking/dangerous/safe verdicts,
  and `--rule considerUsage` reclassifies changes against **real
  schema usage data** — evidence that usage-aware classification is
  the ecosystem's endgame, and that it needs usage data.
- **Buf `buf breaking`** (protobuf): severity by what consumers
  actually depend on (generated source vs wire format) — evidence
  that the *same change* has different severity per consumer type.
- **japicmp / Revapi** (Java binary compatibility): a documented
  study (Commons RNG) shows the two tools disagree on edge cases and
  both produce false positives — evidence that classification must be
  deterministic but conservative, with a human-review tier.
- **Confluent Schema Registry** (event contracts): BACKWARD / FORWARD
  / FULL compatibility modes — evidence that breaking-ness is
  **directional** (producer vs consumer roles).
- **Backstage software catalog**: `consumesApi` / `apiConsumedBy` —
  the ecosystem's established declarative shape for "who consumes
  this API" (the registry mirrors it).
- **Generated-client drift** (OpenAPI Generator's `ensure-up-to-date`,
  kubeflow/pipelines, iris-ui's `check-api-types-drift.sh`): the
  ecosystem standard is regenerate-and-diff in CI.

## Alternatives

Five approaches were compared:

- **A — pure contract diff** (reimplement oasdiff): total overlap, no
  differentiation. Rejected.
- **B — diff + explicit consumer registry**: accurate (the registry is
  ground truth for who consumes what), explainable, low complexity,
  local-first. False negatives = unregistered consumers (a documented
  boundary). **Chosen as the core.**
- **C — diff + static consumer discovery** (source analysis):
  cross-language, fragile, high complexity, and it duplicates a
  sibling dependency-graph project's territory. Rejected for the
  core; possible as a future signal provider.
- **D — diff + generated-client metadata**: the ecosystem pattern
  (regenerate-and-diff) — belongs in a later increment; the tool
  should consume the pattern, not invent one.
- **E — hybrid**: justified *incrementally* — B now, D later,
  usage graphs as groundwork. Building everything upfront would
  violate "complexity must have a reason".

## Decision

**B now, D later** (the hybrid's first two steps). The product is the
consumer-mapping + explainable pre-merge warning layer; without it,
the tool would duplicate oasdiff and should not exist.

## Why

The survey validated the gap with boundary conditions: pure diff is
solved (oasdiff/openapi-diff); consumer *tracking* exists as a
declarative shape (Backstage) but does no diffing; consumer
*verification* exists for Pact-adopting teams (PactFlow) but needs a
broker and per-consumer authoring. No researched tool combines a
deterministic classification of a change with declared-consumer
mapping and an explainable pre-merge warning, locally, with no
services. The nearest thing (PactFlow BDCT) is a gate, not an
explainer, and it is not local-first.

## Implementation

Everything the decision implies: the canonical model, the structural
diff engine, the three-verdict classifier, the consumer registry and
mapper, and the reporters — see [architecture.md](../architecture.md).

## Verification

- The survey's own claims were checked against each tool's
  documentation and release data at the time of research.
- The registry approach was exercised against a real API document
  during development: a simulated required-field change mapped to both
  declared consumers in human and JSON reports.
- The classification layer's correctness is pinned separately (see
  [classification-strategy.md](classification-strategy.md)).

## Consequences

- **The existence justification is sharp:** drop the consumer-mapping
  layer and the tool has no reason to exist. The honest-confidence
  boundary ("unregistered consumers are invisible") is stated in every
  report.
- **Uncertainty has a home:** the survey's false-positive evidence
  (japicmp/Revapi) became the argument for the `review` verdict tier.
- **Directionality became a first-class concept** (Schema Registry,
  Buf evidence) — the ruleset encodes it explicitly.
- **The ecosystem's dead-end is recorded:** Optic's archival removed
  the main incumbent, which sharpened the gap claim — and its
  retrospective is the canonical example of why the problem matters.
