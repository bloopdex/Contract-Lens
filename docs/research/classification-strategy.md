# Research record — the classification strategy

## Question

What makes a contract change **breaking**, and what should the tool do
when the contract evidence cannot determine it?

## Research

- **The incumbent model:** oasdiff's ERR/WARN severity tiers over
  300+ rules, with `x-stability-level` gates (draft/alpha surfaces
  exempt from breaking verdicts) and an `x-extensible-enum` opt-in for
  enum widening. Studied as the reference; its verdict model has no
  tier for "insufficient evidence".
- **False-positive evidence:** a documented japicmp/Revapi comparison
  (Commons RNG) shows two mature binary-compatibility tools disagree
  on edge cases and both produce false positives requiring per-project
  exclusions. Conclusion: compatibility classification must be
  deterministic but conservative, and uncertain cases must surface as
  review-required — never silently.
- **Directionality evidence:** Confluent Schema Registry's
  BACKWARD/FORWARD/FULL modes and Buf's FILE/PACKAGE/WIRE_JSON/WIRE
  categories both encode the same insight — breaking-ness depends on
  *which side* of the contract the change sits on and *what the
  consumer does* with it. For requests the consumer sends and the
  provider validates; for responses the provider sends and the
  consumer reads.
- **Contextual cases:** unknown-field tolerance (is a removed request
  property breaking?) and enum strictness (is a new response enum
  value breaking?) are provider-runtime properties that a static
  contract does not carry — the evidence that a `review` tier is
  structurally necessary, not an option.
- **Rename ambiguity:** renaming a field is indistinguishable from
  remove + add in a static diff; determining break-vs-new-plus-
  deprecated from structure alone is the hardest classification
  problem.

## Alternatives

- **Adopt oasdiff's verdict model wholesale:** closest, but ERR/WARN
  are both verdicts — neither means "a human must look". Rejected.
- **Per-format rules from day one:** premature (no non-OpenAPI format
  existed yet — ADR-004). Rejected.
- **Binary breaking/non-breaking only:** rejected on the
  false-positive evidence above.
- **The three-verdict model (breaking / non-breaking / review):**
  chosen — decisive where the evidence is decisive, honest where it
  is not.

## Decision

ADR-001: direction-aware, deterministic rules with three verdicts;
semver **derived** from the verdict (breaking → major, additive
non-breaking → minor, other non-breaking → patch, review → none);
rename candidates (same-type add/remove pairs) always `review`;
undetermined-direction and unrecognized changes always `review`.

## Why

A wrong `breaking` verdict creates noise and erodes trust; a wrong
`non-breaking` verdict ships a break. The `review` tier is the only
verdict that is honest when the contract does not carry the runtime
behavior a rule needs. Deterministic rules (over any probabilistic
scoring) make the output reproducible evidence — testable in CI,
reviewable by humans.

## Implementation

`Classifier` in `:core` — the full rule reference with reasons:
[classification.md](../classification.md). Direction comes from the
change location grammar (`→ request body`, `→ parameter`,
`→ response`).

## Verification

- The 26-case fixture corpus pins every rule with expected verdicts
  (one pair per rule, non-breaking variants included).
- Property invariants: determinism, totality, purity, verdict/semver
  consistency, rename-pairing determinism.
- Recorded sweep: 200,000 classifier invariant iterations, clean.
- Real-surface dogfooding: a required-field change classified
  breaking/major with the correct exit code.

## Consequences

- `review` verdicts require human attention — the accepted cost of
  honesty; an override mechanism (ignore lists) was recorded as future
  work and has not been built.
- Conservative defaults may flag changes some teams consider safe
  (response enum additions, request property removals) — documented,
  configurable later.
- The `x-stability-level` exemption was **not** implemented: the
  canonical model carries no stability levels (a documented
  limitation — [limitations.md](../limitations.md)).
- Revisit condition recorded in ADR-001: per-consumer verdict
  re-ranking once real usage data exists.
