# ContractLens ADR index

The design record for ADR-001..006 lives in the BloopLab Logseq graph
(per the graph convention — see the Phase 0 page); Phase 6 ADRs are
written in this directory. Each entry below states its status and where
the full text lives.

| ADR | Title | Status | Where |
|---|---|---|---|
| ADR-001 | Contract-diff model and classification ruleset | Accepted (Phase 0, implemented Phase 4) | Logseq: `ContractLens / Phase 0 - Research & Contract Model` |
| ADR-002 | Consumer strategy: explicit registry, not static discovery | Accepted (Phase 0, implemented Phase 3) | Logseq: Phase 0 page |
| ADR-003 | Snapshot strategy: file-backed, SHA-keyed, content-hash-verified | Accepted (Phase 0, implemented Phase 1) | Logseq: Phase 0 page |
| ADR-004 | Format scope: OpenAPI 3.0/3.1 only for the MVP | Accepted (Phase 0; GraphQL/JSON Schema landed in Phase 4 per its revisit path) | Logseq: Phase 0 page |
| ADR-005 | Technology: Kotlin + Gradle, swagger-parser, Clikt, kotlinx + kaml, kotest; Jazzer for fuzzing | Accepted (Phase 0; Jazzer lands in Phase 6 CI per the Phase 5 deferral) | Logseq: Phase 0 page |
| ADR-006 | Generated-client diffing: deterministic projection, never parsing generated output | Accepted (Phase 4; recorded numbering collision — the page's "ADR-002" was already the consumer strategy) | Logseq: `ContractLens / Phase 4 - Generated Clients & Extended Contracts` |
| ADR-007 | Packaging and release: fat JAR primary artifact, tag-driven releases with checksums | Accepted (Phase 6) | [007-packaging-and-release.md](007-packaging-and-release.md) |
| ADR-008 | DeployScore feed: signal contract now, local emitter only, no remote integration until DeployScore exists | Accepted (Phase 6) | [008-deployscore-feed.md](008-deployscore-feed.md) |

Convention: new ADRs are numbered sequentially; superseded decisions are
marked (superseded by ADR-NNN) rather than deleted.
