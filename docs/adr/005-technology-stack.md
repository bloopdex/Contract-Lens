# ADR-005 — Technology: Kotlin + Gradle, swagger-parser, Clikt, kotlinx-serialization + kaml, kotest; Jazzer for fuzzing

- **Date:** 2026-08-19
- **Status:** Accepted (amended — see below)
- **Related:** ADR-001 (the model the parser must not leak into), ADR-004 (OpenAPI scope), ADR-007 (distribution)

## Context

Language and library choices shape everything downstream: the typed
canonical model, the parser facade, the CLI, and the test stack. The
author's projects sit on the JVM; the pure-diff reference ecosystem is
Go-centric. The choices were validated, not assumed.

## Decision

1. **Kotlin + Gradle**, multi-module, JVM 17 toolchain (CI also tests
   on 21, Linux and Windows). Strong static typing over contract
   models; JVM proximity to the Spring-based projects this tool guards.

2. **Parsing: swagger-parser** (`io.swagger.parser.v3`) — actively
   maintained, OpenAPI 3.1 support, Apache-2.0, resolve/flatten
   options. The parsing POJOs are swagger-core's; the canonical model
   is ContractLens's own. The parser is a facade, **not** the model —
   dependency direction guarantees the domain never leaks swagger-core
   types.

3. **CLI: Clikt** (the de-facto standard). **Serialization:**
   kotlinx-serialization + kaml (YAML registry/usage files). **Tests:**
   kotest + JUnit, kotest-property for property-based invariants.

4. **Fuzzing: Jazzer** — a JVM fuzzer with first-class Kotlin support
   and OSS-Fuzz integration, evaluated for the untrusted-input parser
   and adopted for the coverage-guided layer (see the amendment).

5. **Ktor: deferred** — no server exists in the CLI; pulling it in
   would be a speculative dependency.

## Alternatives considered

- **Go** (the reference diff tool's ecosystem): rejected — duplicates
  that environment and loses JVM library maturity (swagger-parser).
- **Rust**: rejected — no first-class OpenAPI parsing story.
- **KaiZen openapi-parser**: rejected — last release ~5 years old.

## Consequences

- JVM startup cost is a measured metric, not an assumed problem
  ([performance.md](../performance.md)).
- Revisit when: distribution size or startup becomes a real complaint
  (see ADR-007's native-image rejection); Ktor when a server-side feed
  endpoint appears.

## Amendment (2026-08-20) — Jazzer adopted

The original decision anticipated fuzzing "for the untrusted-input
parser", with coverage-guided fuzzing deferred to CI. That deferral
was executed: the test-only `:fuzz` module
(jazzer-junit 0.23.0) holds six `@FuzzTest` targets (the five parser
boundaries plus snapshot round-trip), seeded corpora committed,
regression replay on every build, bounded coverage-guided runs in
nightly CI. Target selection is documented in
[fuzzing.md](../fuzzing.md): the diff engine, classifier, and mapper
were deliberately not targeted — their inputs are typed domain data,
not bytes.

## Verification

- The stack itself is the running evidence: clean build, 329 tests,
  ktlint, coverage gate (see [testing.md](../testing.md)).
- swagger-parser upgraded 2.1.27 → 2.1.40 → 2.1.44 under the
  dependency policy ([security.md](../security.md)); parser and
  fixture suites re-verified green after each upgrade.
- Jazzer: 2,573,262 recorded executions across the six targets, 0
  crashes.
