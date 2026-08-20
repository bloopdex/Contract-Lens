# Machine-readable output formats

Every JSON output is **versioned and additive-only within a version**:
fields are added, never removed or re-interpreted, so a consumer
written against version N keeps working for the life of that version.
All reports are deterministic — identical inputs produce identical
bytes except where a field is explicitly variable metadata
(`analyzedAt` in signals, `capturedAt` in snapshots).

| Format | Version | Produced by |
|---|---|---|
| snapshot document | 1 | `snapshot` |
| `contractlens-diff` | 1 | `diff --json` |
| `contractlens-diff` | 2 | `diff --classify --json` |
| `contractlens-impact` | 2 | `impact --json` |
| `contractlens-generated-diff` | 1 | `generated-diff --json` |
| `contractlens-signal` | 1 | `signal` |

## snapshot document (v1)

The stored canonical form (also the diff/impact/signal inputs):

```json
{
  "formatVersion": 1,
  "contract": "example-api",
  "sourcePath": null,
  "identity": {"kind": "git-commit", "sha": "<40-hex>"},
  "capturedAt": "<ISO-8601 — variable metadata>",
  "contentHash": "<sha256 over the deterministic envelope>",
  "surface": {"name": "...", "kind": "openapi", "formatVersion": "3.0.3", "operations": [...]}
}
```

The content hash covers everything except `capturedAt`; loading always
re-verifies it.

## contractlens-diff v1 / v2

- **v1** (`diff --json`): `format`, `version`, `old`/`new` identities
  (contract + sha), `summary` (total/added/removed/changed), and the
  `changes` list (kind, target, location, sourceLocation, from/to,
  explanation). `verdict` is null on every change — structural facts
  only.
- **v2** (`diff --classify --json`): everything from v1 plus
  `classification` (breaking/non-breaking/review counts + derived
  semver) and the `classified` list (each change with `verdict`,
  `semver`, and the deterministic `reason`).

## contractlens-impact v2

`format`, `version`, old/new identities, `registry` (path), `summary`
(changes, affectedConsumers, mappedChanges, verdict counts, semver),
the complete change set (unmatched changes stay visible), the
`classified` list, per-consumer `impacts` (each impact carries its
mapping reasons), and the honesty note.

## contractlens-generated-diff v1

The projected-surface diff report: old/new identities, the projection
style, summary counts, and the change set of the projection diff
(with classification when `--classify` is passed).

## contractlens-signal v1

The DeployScore feed payload — see
[deployscore-feed.md](deployscore-feed.md) for the full shape, privacy
boundary, and versioning rules.
