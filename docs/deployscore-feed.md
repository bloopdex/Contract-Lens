# contractlens-signal v1 — the proposed DeployScore feed

Status: **proposed producer-side contract** (ADR-008). Emitted by the
`contractlens signal` command; the receiving side does not exist
yet — DeployScore exists as design notes only (no repository, no
API). When DeployScore implements its receiving end, it will
consume THIS payload as the ContractLens signal (its integration design
assigns the consumer to DeployScore itself).

## Design principles

- **Metadata only.** Counts, verdicts, semver, operation identities
  (canonical `METHOD /pathIdentity` form), registry-declared consumer
  ids. No property names, no values, no descriptions, no examples, no
  classification prose — the privacy boundary is tighter than the diff
  report's, and it is pinned by tests in both `:core` and `:cli`.
- **Deterministic.** Identical analysis inputs produce identical bytes;
  `analyzedAt` is the single variable-metadata field (like the snapshot
  format's `capturedAt`).
- **Derived, never duplicated.** Every field comes from the existing
  classification report and (optionally) the impact report. The
  metrics events (`contract_changes_detected`, …) remain the tool's own
  telemetry; the signal is a separate versioned document, not a second
  telemetry model.
- **Offline-safe.** Emission is stdout or a file. There is no network
  path, so DeployScore being absent or unreachable cannot affect local
  analysis — the analysis completes before emission.

## Payload

```json
{
  "format": "contractlens-signal",
  "version": 1,
  "producer": "contractlens",
  "producerVersion": "0.1.0",
  "contract": "example-api",
  "analyzedAt": "2026-08-19T22:10:00Z",
  "old": {"contract": "example-api", "sha": "<40-hex>"},
  "new": {"contract": "example-api", "sha": "<40-hex>"},
  "changes": {"total": 3, "breaking": 1, "nonBreaking": 1, "review": 1},
  "semver": "major",
  "operations": [
    {
      "identity": "GET /users",
      "totalChanges": 1,
      "breakingChanges": 1,
      "nonBreakingChanges": 0,
      "reviewChanges": 0
    }
  ],
  "consumers": [
    {"id": "example-frontend", "kind": "frontend", "affectedChanges": 2, "breakingChanges": 1}
  ],
  "metrics": {"analysisDurationMs": 41.5}
}
```

Field semantics:

- `semver` — the derived semver level (`major` | `minor` | `patch`), or
  null when no change carries a label (review-only change sets).
- `operations` — one entry per changed operation, sorted by canonical
  identity; changes outside the location grammar carry no operation and
  are counted only in the totals (never guessed).
- `consumers` — present only when `--registry` was given; sorted by
  consumer id. `kind` is the registry-declared kind
  (`frontend` | `service` | `sdk` | `generated-client` | `integration`).
- Versioning: additive changes only within v1; a breaking shape change
  bumps `version`.

## Usage

```
contractlens signal old.snapshot.json new.snapshot.json                    # payload on stdout
contractlens signal old.snapshot.json new.snapshot.json --registry reg.yaml
contractlens signal old.snapshot.json new.snapshot.json --output feed.json # file
```

Exit codes follow the CLI contract: `0` success, `1` breaking changes
detected, `2` operational error (including `OUTPUT_UNWRITABLE`).

## What is deliberately NOT here

- No HTTP client, no endpoint, no auth — the transport is defined when
  DeployScore's API exists. ADR-008 records the revisit
  condition.
- No raw contract content, no registry contact/notes fields, no
  source paths (privacy: the feed is designed to be safe to hand to a
  remote scoring service).
