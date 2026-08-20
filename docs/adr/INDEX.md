# ContractLens ADR index

Every load-bearing architectural decision is an ADR in this directory,
numbered sequentially. The full texts of ADR-001..006 originally lived
in the project's research notes and were moved into the repository so
the reasoning ships with the code.

| ADR | Title | Status |
|---|---|---|
| ADR-001 | [Contract-diff model and classification ruleset](001-contract-diff-model.md) | Accepted |
| ADR-002 | [Consumer strategy: explicit registry, not static discovery](002-consumer-registry.md) | Accepted |
| ADR-003 | [Snapshot strategy: file-backed, SHA-keyed, content-hash-verified](003-snapshot-strategy.md) | Accepted |
| ADR-004 | [Format scope: OpenAPI 3.0/3.1 for the MVP, canonical model designed format-neutral](004-format-scope.md) | Accepted (amended — GraphQL/JSON Schema adapters added) |
| ADR-005 | [Technology: Kotlin + Gradle, swagger-parser, Clikt, kotlinx + kaml, kotest; Jazzer for fuzzing](005-technology-stack.md) | Accepted (amended — Jazzer adopted) |
| ADR-006 | [Generated-client diffing: deterministic projection, never parsing generated output](006-generated-client-projection.md) | Accepted (recorded numbering collision) |
| ADR-007 | [Packaging and release: fat JAR primary artifact, tag-driven releases with checksums](007-packaging-and-release.md) | Accepted |
| ADR-008 | [DeployScore feed: signal contract now, local emitter only, no remote integration until DeployScore exists](008-deployscore-feed.md) | Accepted |

Conventions:

- Decisions are never silently rewritten. When a decision evolves, an
  **amendment** is appended to the ADR, dated and attributed (ADR-004,
  ADR-005 are examples).
- A rejection is a decision too, and it records its **revisit
  conditions** (ADR-005's Ktor deferral, ADR-006's byte-exact
  regeneration).
- Superseded decisions are marked (superseded by ADR-NNN) rather than
  deleted.

The research behind each decision lives in [docs/research/](../research/)
and, at greater depth, in the project's research notes; the
implementation consequences are documented in the concept and
engineering docs they cite.
