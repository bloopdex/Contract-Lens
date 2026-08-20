# ADR-003 — Snapshot strategy: file-backed, SHA-keyed, content-hash-verified

- **Date:** 2026-08-19
- **Status:** Accepted
- **Related:** ADR-001 (the canonical model a snapshot serializes), ADR-007 (release reproducibility)

## Context

Diffing needs two captured points in time of one contract. The capture
mechanism must be reproducible (the same spec at the same point in
history always yields the same snapshot), tamper-evident (a corrupted
or hand-edited snapshot must never be silently diffed), and free of
operational overhead (no services, no databases — the tool is
local-first).

## Decision

1. **A snapshot is a canonical-model serialization** stamped with
   `formatVersion`, the contract name, an identity (kind
   `git-commit` + SHA), `capturedAt`, and a content hash computed over
   everything except the timestamp.

2. **The identity key is contract + git commit SHA**, stored as
   `<contract>@<sha>.snapshot.json` in a plain directory. The store
   index is rebuilt by directory scan on every start — true by
   construction; corrupt entries are listed with their error, never
   hidden.

3. **Loading always re-verifies the content hash.** A corrupted or
   hand-edited snapshot is refused loudly (`SNAPSHOT_INTEGRITY`) and
   never diffed.

4. **Canonical serialization makes captures deterministic:**
   byte-identical inputs produce byte-identical snapshots
   (test-pinned).

5. **`formatVersion` gates loading; format changes are additive-only.**

## Alternatives considered

- **Explicit version identifiers**: rejected — extra bookkeeping with
  no demonstrated need; the git commit SHA is the natural key in a
  pre-merge workflow and needs zero maintenance.
- **Database-backed snapshot history**: rejected — a database adds
  state and operations for no demonstrated need in a local CLI.

## Consequences

- Comparing non-git contexts requires an explicit identity override
  (`--sha`) — accepted.
- Startup cost grows with the number of snapshots (directory scan);
  fine at personal/team scale by construction.
- Revisit when: snapshot counts grow large, or non-git consumers of
  the store appear.

## Verification

- `SnapshotStoreTest`: round-trip, `capturedAt` excluded from the
  hash, tamper detection, corrupt JSON, format-version refusal, index
  rebuild with corrupt entries, store path handling.
- Path-escape regression test: a contract name shaped like a path
  cannot escape the store directory.
- Determinism: two independent captures of the same spec produce the
  identical content hash (real-CLI verification).
- Snapshot integrity is never bypassed by any command (CLI-tested:
  tampered snapshots fail with exit 2).
