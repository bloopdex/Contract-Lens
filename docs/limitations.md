# Known limitations

Limitations are stated the same way everywhere in this project: what
is missing, why, what ContractLens does instead, and what would remove
the limitation. This page is the consolidated list; the reasoning
behind each boundary lives in the cited ADR or doc.

## Contract formats

- **Local `$ref`s only.** Multi-file and remote references are rejected
  (`UNSUPPORTED_REFERENCE`). *Why:* remote resolution implies network
  code paths and trust decisions the local-first model deliberately
  avoids (ADR-003). *Instead:* local references resolve with cycle and
  depth guards. *To remove:* an explicit, offline multi-file bundle
  format would be needed first.

- **Core JSON Schema vocabulary only.** Composition keywords
  (`allOf`/`anyOf`/`oneOf`) and annotations beyond `default` are not
  mapped, so changes there produce no diff. *Why:* the adapter maps the
  vocabulary the canonical model represents; the rest was deferred with
  the format (ADR-004 amendment). *Instead:* the adapter ignores what
  it cannot represent — a documented boundary, not a silent
  mis-diff. *To remove:* extend the canonical model additively, then
  the adapter.

- **Single-file GraphQL SDL only.** No schema stitching or multi-file
  imports. *Why:* the adapter covers the core type system; imports add
  a resolution layer with no demonstrated need. *Instead:* single-file
  schemas only, rejected loudly otherwise.

- **Swagger 2.0 is rejected before conversion** — deliberately: the
  parsing library would silently convert it. The tool targets OpenAPI
  3.0/3.1 (ADR-004).

## Semantic coverage

- **`x-stability-level` exemptions are not implemented.** The canonical
  model carries no stability levels, so draft/alpha surfaces get the
  same verdicts as stable ones. *Why:* the model captures structural
  facts only; stability annotations were out of MVP scope. *Instead:*
  the verdict carries its reason; a human can override. *To remove:*
  add the annotation to the model additively and teach the classifier
  the exemption.

- **Rename detection is candidate-only.** Same-type add/remove pairs
  are flagged `review` for both members; renames are never confirmed or
  auto-classified. *Why:* static evidence cannot distinguish a rename
  from removal+addition, and a wrong guess in either direction erodes
  trust (ADR-001). *To remove:* an override mechanism (ignore lists) —
  recorded as future work since ADR-001.

- **The `review` verdict has no override mechanism yet.** *Why:*
  recorded as accepted future work at ADR-001; the verdict model was
  the MVP priority. *Instead:* review verdicts are deterministic and
  explained, so a human can judge them quickly.

## Directionality

- **Undetermined-direction changes fall back to `review`.** The
  classifier never guesses a direction; changes outside the location
  grammar are surfaced for human judgment. *Why:* conservative by
  design (ADR-001).

## Generated clients

- **Convention-stable, not byte-exact.** The projection models
  generator conventions; it does not claim to match any generator
  version's output. *Why:* byte-exactness would require running or
  parsing generators — both rejected (ADR-006). *Instead:* deterministic
  projection diffing with client-shaped locations. *To remove:* extend
  the projection if generator-faithful output ever has a demonstrated
  need.

- **Model type names are not reported.** `$ref`s resolve inline, so
  method-level and inline schema changes are reported, not "type X
  changed". *Why:* a consequence of the canonical model's reference
  resolution (ADR-001). *To remove:* a projection-level type-name
  inventory.

## Usage graph

- **Not wired into classification.** *Why:* no real usage producers
  exist; wiring against an empty dataset would be an untested
  heuristic. *Instead:* the format, validation, and merging are
  implemented and tested; classification runs against the whole
  contract. *To remove:* a real usage producer — see
  [usage-graph.md](usage-graph.md).

## Impact analysis

- **Unregistered consumers are invisible.** *Why:* declared knowledge
  is the only reliable knowledge (ADR-002). *Instead:* every report
  states the boundary. *To remove:* a catalog import (the registry
  shape mirrors Backstage's `consumesApi` for exactly this).

- **Contract renames are not mapped.** `impact` compares two snapshots
  of the same contract; different names fail with `CONTRACT_MISMATCH`.
  *Why:* mapping is keyed by contract identity. *To remove:* explicit
  rename declarations in the registry.

- **Consumer `kind` does not change verdicts.** The kind is recorded
  and reported; verdicts are kind-independent. *Why:* per-kind severity
  (the Buf evidence) was recorded as research but the registry v1
  delivers the structural layer. *To remove:* per-kind rule
  adjustments, additive.

## Input boundaries

- **Inputs are bounded** at 10 MB (`INPUT_TOO_LARGE`) with 64-level
  depth guards. Oversized or pathologically nested documents fail
  loudly. *Why:* contracts are untrusted input
  ([security.md](security.md)). *Instead:* a real-world API dump
  (~73 KB) is far under the limit; normal use is unaffected.

- **Symlink-specific handling and permission-edge testing** remain open
  follow-ups (recorded in the security review).

## Operational

- **CI workflows and release publication execute once the repository
  is hosted.** Every step is implemented and verified locally
  (ADR-007); nothing is claimed as published until it is.

- **The DeployScore feed is a producer-side contract.** The receiving
  service does not exist yet; the emitter is offline by construction
  (ADR-008). *To remove:* DeployScore's API — the revisit condition is
  recorded.

- **Strict OpenAPI validation can refuse real-world documents** with
  undeclared path parameters (recorded against a real API dump). The
  failure is loud and typed (`INVALID_STRUCTURE`) — the fix belongs to
  the document's source, and ContractLens refuses rather than guessing.
