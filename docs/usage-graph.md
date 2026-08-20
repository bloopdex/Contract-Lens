# The usage graph — an integration boundary

The usage graph records **which fields a consumer actually reads**, per
operation and direction. It is the designed substrate for future
usage-aware classification ("warn only when actually-read fields
change"), and it is **deliberately not wired into classification
today**.

## What exists

A validated, versioned YAML format plus a typed model and strict
validation (the `:registry` adapter, same boundary as the consumer
registry):

```yaml
version: 1
consumers:
  - id: example-frontend          # the consumer's registry id
    contract: example-api
    operations:
      - operation: GET /users/{id}        # canonical selector (registry identity)
        requestFields:                    # dotted property paths the consumer reads
          - name
        responseFields:
          - email
          - profile.address.city
```

Semantics:

- records are per `(consumer, contract)`;
- operation selectors reuse the **registry's canonical identity**, so a
  usage record and a registry selector always mean the same operation;
- field paths are dotted property chains relative to the request body
  or response schema;
- duplicate operations for one consumer **merge deterministically**
  (field lists union); duplicate `(consumer, contract)` records **fail**
  — identity is ambiguous;
- validation is strict (`USAGE_*` typed errors): blank paths, malformed
  paths, missing fields, unsupported versions all fail loudly.

## What does not exist — and why

Usage-aware classification is **not wired in**. The decision is
recorded with its evidence:

- real usage data does not exist — nothing records which fields
  consumers actually read, so the format has **no producers**;
- wiring classification against an empty dataset would be an untested
  heuristic, violating the deterministic, explainable ruleset contract
  (ADR-001).

The precedent is documented in the research
([usage-graph.md](research/usage-graph.md)): the ecosystem's
usage-aware reclassification (graphql-inspector's `considerUsage`)
exists precisely because that ecosystem *has* usage data. Until a
producer appears, classification runs against the whole contract, and
the graph remains a documented integration boundary.

## The revisit condition

When a real usage producer exists — an instrumentation layer or an
analyzer that records field reads — the classifier can be taught to
weight changes by actual reads (verdicts re-ranked per consumer, the
revisit condition already noted in ADR-001). The format and validation
are in place so that wiring is an integration step, not a format
design.

## Verification

- Format + validation unit tests (parse, strict-mode failures, merge
  determinism, duplicate-record refusal).
- Fuzzed at the parser boundary (`fuzzUsageGraph` Jazzer target and the
  seeded harness — [fuzzing.md](fuzzing.md)).
