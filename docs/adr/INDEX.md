# ContractLens ADR index

ADR-001..006 were recorded in the project's research notes (the Logseq
graph, per the convention at the time); ADR-007 and ADR-008 are written
in this directory. Each entry below states its status and where the
full text lives.

| ADR | Title | Status | Where |
|---|---|---|---|
| ADR-001 | Contract-diff model and classification ruleset | Accepted | Research notes: `ContractLens / Research & Contract Model` |
| ADR-002 | Consumer strategy: explicit registry, not static discovery | Accepted | Research notes: `ContractLens / Research & Contract Model` |
| ADR-003 | Snapshot strategy: file-backed, SHA-keyed, content-hash-verified | Accepted | Research notes: `ContractLens / Research & Contract Model` |
| ADR-004 | Format scope: OpenAPI 3.0/3.1 only for the MVP | Accepted (later extended: GraphQL and JSON Schema adapters per its revisit path) | Research notes: `ContractLens / Research & Contract Model` |
| ADR-005 | Technology: Kotlin + Gradle, swagger-parser, Clikt, kotlinx + kaml, kotest; Jazzer for fuzzing | Accepted | Research notes: `ContractLens / Research & Contract Model` |
| ADR-006 | Generated-client diffing: deterministic projection, never parsing generated output | Accepted (recorded numbering collision — the note's "ADR-002" was already the consumer strategy) | Research notes: `ContractLens / Generated Clients & Extended Formats` |
| ADR-007 | Packaging and release: fat JAR primary artifact, tag-driven releases with checksums | Accepted | [007-packaging-and-release.md](007-packaging-and-release.md) |
| ADR-008 | DeployScore feed: signal contract now, local emitter only, no remote integration until DeployScore exists | Accepted | [008-deployscore-feed.md](008-deployscore-feed.md) |

Convention: new ADRs are numbered sequentially; superseded decisions are
marked (superseded by ADR-NNN) rather than deleted.
