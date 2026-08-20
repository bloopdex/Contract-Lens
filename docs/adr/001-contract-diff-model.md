# ADR-001 — Contract-diff model and classification ruleset

- **Date:** 2026-08-19
- **Status:** Accepted
- **Related:** ADR-004 (format scope), ADR-002 (consumer strategy), ADR-006 (generated-client diffing)

## Context

A contract change breaks consumers at runtime, not at build time: each
consumer compiles against its own copy of the contract, so a field
removed in the provider breaks a client's request with nothing in the
raw diff to warn anyone. What a developer needs after a change, in
order, is: (1) what changed structurally, (2) whether it breaks
existing consumers, (3) which known consumers it hits, (4) why — with
the exact schema path. This decision covers (1) and (2): the structural
diff model and the compatibility ruleset.

## Decision

1. **One canonical, format-neutral contract model.** Every input format
   maps into one surface model — operations, parameters, request
   bodies, responses, recursive schema nodes (types, enums,
   constraints, nullability) — so the diff engine, the classifier, and
   the consumer mapper are written exactly once. The full model is
   documented in [contract-model.md](../contract-model.md).

2. **Structural identity.** Operation identity = `(method, normalized
   path template)`: `/users/{id}` and `/users/{userId}` are the *same
   operation*, and a template-variable rename is non-breaking.
   Property identity = name within its schema node; enum identity =
   value. References resolve for comparison, and the ref target name is
   preserved for explanation.

3. **The diff reports structural facts only.** 26 change kinds with
   precise locations; no verdicts. Compatibility is a separate layer,
   so the engine's output is never judgment.

4. **Three-verdict classification, direction-aware, deterministic.**
   `breaking` / `non-breaking` / `review`, with direction (request: the
   consumer sends, the provider validates; response: the provider
   sends, the consumer reads). Conservative rule: when the contract
   evidence is insufficient, the verdict is `review` — never a silent
   guess in either direction. Semver is **derived** from the verdict
   (breaking → major, additive non-breaking → minor, other
   non-breaking → patch, review → no label). The full rule reference is
   [classification.md](../classification.md).

5. **Renames are never inferred.** A same-type property add/remove pair
   inside one schema is a rename *candidate*: both changes get
   `review`, never an automatic breaking verdict.

## Alternatives considered

- **Adopt oasdiff's verdict model wholesale** (ERR/WARN severity
  tiers, 300+ rules): closest existing model, but it has no tier for
  "the contract evidence is insufficient — a human must look"; both
  ERR and WARN are verdicts.
- **Per-format rules from day one**: premature — it would duplicate the
  ruleset per format before any non-OpenAPI format existed (ADR-004).
- **Binary breaking/non-breaking only**: rejected — a forced guess on
  contextual cases would erode trust. The recorded japicmp/Revapi
  evidence (two mature binary-compat tools disagreeing on edge cases
  and producing false positives) shows that even well-studied
  compatibility tools need a human-review tier.

## Consequences

- `review` verdicts require human attention; an override mechanism
  (ignore lists) was accepted as future work and has not been built.
- Conservative defaults may flag changes some teams consider safe
  (e.g. response enum additions) — documented, configurable later.
- Revisit when: real usage data exists — verdicts may then be
  re-ranked per consumer (the usage-graph integration boundary,
  [usage-graph.md](../usage-graph.md)).

## Verification

- The ruleset is pinned by the 26-case fixture corpus
  (`cli/src/test/resources/fixtures/classify/`, one pair per rule,
  expected verdicts reviewed like code).
- Property invariants (`ClassifierPropertyTest`): determinism,
  totality, purity, verdict/semver consistency, rename-pairing
  determinism.
- Recorded sweep: 200,000 classifier invariant iterations, clean
  (see [fuzzing.md](../fuzzing.md)).
