# ADR-008 — DeployScore feed: define the signal contract now, emit locally, no remote integration until DeployScore exists

- **Status:** Accepted (2026-08-19)
- **Deciders:** ContractLens maintainer
- **Related:** ADR-001 (classifier), ADR-002 (registry), ADR-007 (release);
  DeployScore design notes (research notes)

## Context

The project roadmap lists a "DeployScore feed" goal and asks for "the
DeployScore feed contract (event payload: breaking-change summary,
affected consumers)" plus a feed emitter.

Inspection of the actual DeployScore project (2026-08-19): **DeployScore has
no repository, no running API, and no implemented signal schema.** It exists
as design notes only (no repository, `POST /v1/score` sketched, pluggable
providers with degraded mode). Its integration design assigns the
ContractLens consumer to DeployScore's own future work: "breaking-change
report → API contract change signal".

A sibling project's decision (Recall ADR-0029) reached the same situation
and decided the portable export is the defined future boundary — no feed
integration against a nonexistent receiver.

## Decision

1. **ContractLens defines and publishes the signal payload now** —
   `contractlens-signal` v1, a versioned JSON document carrying analysis
   metadata (change counts by verdict, derived semver level, affected
   consumer ids, duration; see `docs/deployscore-feed.md` for the full
   shape). Defining the producer side early means DeployScore's future
   integration consumes a real, tested format instead of inventing one.

2. **The emitter is local-only and offline-safe.** `contractlens signal
   <old> <new> [--registry] [--output <file>]` runs the normal analysis
   pipeline and writes the payload to stdout (default) or a file. There is
   **no network integration** — no HTTP calls, no auth, no retries — until
   DeployScore's API actually exists (its API/webhook work will define the
   transport). A DeployScore that does not exist or is unreachable can never
   affect local analysis, because the analysis is complete before emission.

3. **Privacy boundary:** the payload contains only analysis metadata —
   counts, verdicts, operation identities, registry-declared consumer ids —
   never descriptions, examples, property values, or raw contract content
   (those never enter the canonical model; the redaction boundary is pinned
   by tests, and the signal suite re-pins it).

4. **The core metrics model stays independent.** The metrics events
   (`contract_changes_detected` etc.) remain the tool's own telemetry; the
   signal payload is derived from the same analysis result but is a separate
   versioned document, not a second telemetry model.

5. **Exit codes follow the existing contract:** 0 success, 1 breaking
   changes detected, 2 operational error (e.g. unwritable output file).

## Alternatives considered

- **HTTP adapter against the design-documented API now:** rejected — the
  endpoint, auth scheme, and payload shape are unimplemented design notes;
  building against them would fabricate an integration contract.
- **No emitter at all:** rejected — the payload shape needs a test-pinned
  reference implementation or DeployScore's integration would inherit an
  unverified format.

## Consequences

- `docs/deployscore-feed.md` is the contract DeployScore's integration will
  be pointed at; the emitter + its tests are the reference implementation.
- Revisit when: DeployScore's API & webhook work is implemented — then add
  an optional transport adapter (env-var-configured endpoint + API key)
  that submits the same payload, failure-safe, without changing local
  behavior.
