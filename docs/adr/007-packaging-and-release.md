# ADR-007 — Packaging and release: fat JAR as the primary artifact, tag-driven releases with checksums

- **Status:** Accepted (Phase 6, 2026-08-19)
- **Deciders:** ContractLens maintainer
- **Related:** ADR-005 (technology), ADR-003 (snapshot strategy)

## Context

Phases 1-5 delivered a working CLI runnable via `gradlew :cli:run`, plus the
Gradle `application` distribution (`installDist`/`distZip`). Phase 6 needs
distribution and a release process: an artifact a user can download and run
without building from source, a reproducible way to produce it, and
checksums so users can verify integrity.

BloopLab precedent: Recall 1.0.7 shipped a release bundle
(`binary + SHA256SUMS + install scripts + CHANGELOG + LICENSE`) built by
`scripts/release.ps1`, with publication deliberately deferred to the repo
hosting decision. That model is proven and is adopted here unchanged.

## Decision

1. **The fat JAR is the primary release artifact.** `:cli:shadowJar`
   (com.gradleup.shadow) produces one self-contained
   `contractlens-<version>-all.jar` runnable with `java -jar` on any JRE 17+.
   The Gradle `application` distribution stays as a secondary artifact
   (script + lib/ layout for people who prefer it); no additional packaging
   ecosystems.

2. **The version lives in exactly one authoritative place:**
   `version = "<x.y.z>"` in the root `build.gradle.kts`. The CLI reads it at
   build time (generated resource) and reports it via `--version`; the release
   script and the release workflow both verify the release tag against that
   literal (strict `vMAJOR.MINOR.PATCH`), mirroring Recall's release.yml.

3. **Releases are tag-driven.** Pushing `vX.Y.Z` triggers
   `.github/workflows/release.yml`: verify tag ↔ build file version → clean
   build + full tests + lint + coverage gate → package → SHA-256 checksums →
   smoke-test the artifact → create the GitHub Release. Nothing commits or
   pushes back; nothing publishes from branches or PRs. Generated artifacts
   live in `dist/`, which is gitignored and never committed.

4. **SHA-256 is the checksum algorithm.** Every artifact gets
   `artifact.sha256` (or a `SHA256SUMS` bundle manifest, Recall-style), and
   the release process verifies the checksum before anything is attached.
   Verification instructions ship in the README.

5. **Reproducible builds are a property to be demonstrated, not claimed.**
   Jar timestamps and entry order are normalized
   (`preserveFileTimestamps = false`, `reproducibleFileOrder = true`) and
   verified by building twice from a clean checkout and comparing hashes.
   Whatever remains nondeterministic gets documented in `docs/release.md`.

6. **A Docker image is provided for CI/container use** (multi-stage build,
   JRE 17 runtime, the same fat JAR), because the Phase 6 page requires
   "the Docker image runs compare" — not as the primary install path.

7. **Publication waits on the repo hosting decision** (BloopLab
   SOURCE-OF-TRUTH open decision #3). The release machinery is complete and
   locally verified; nothing is claimed as published until it is.

## Alternatives considered

- **Native binaries (GraalVM native-image):** rejected — JVM startup cost was
  measured, not assumed (ADR-005), and no distribution complaint exists to
  justify the toolchain complexity.
- **Maven Central publication:** rejected — this is a CLI tool, not a
  library; a jar is not a usable install on Central.
- **Package managers (Scoop/Chocolatey/Homebrew):** future option once
  hosting exists; not needed to satisfy this phase's goals.

## Consequences

- Users need a JRE 17+ — documented in the README.
- The release workflow is the single publication path; ad-hoc releases are
  not supported (the local `scripts/release.ps1` produces the same bundle
  for verification/dogfooding only).
- Revisit when: hosting is decided (then: publication, install scripts
  tested from a real GitHub Release); or distribution size/startup becomes a
  real complaint (ADR-005 revisit condition → native image).
