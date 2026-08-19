# ContractLens — working notes for AI-assisted sessions

## What this project is

BloopLab tool #1 after Recall: local-first API contract impact analysis.
Kotlin + Gradle, multi-module. The canonical design record is the
BloopLab Logseq graph (`s:\Logseq`, pages `ContractLens*`); Phase 0 is
complete, current phase is Phase 1 (Core Foundation).

## Build environment (this machine)

- No JDK on PATH. Use the IntelliJ bundled JBR:
  `$env:JAVA_HOME = "C:\Users\mrben\AppData\Local\Programs\IntelliJ IDEA Ultimate\jbr"`
- `.\gradlew.bat build` compiles, runs ktlint, and runs the full test suite.
- Gradle 9.7.0 wrapper; Kotlin 2.2.0; bytecode target 17 (CI: JVM 17 + 21).

## Rules

- Phase discipline: implement only the current phase. Phase 2 (diff +
  classification), Phase 3 (registry) etc. are explicitly not started.
- No AI/Claude attribution in commits or docs.
- Tests are part of implementation; never weaken tests to pass.
- Determinism is an invariant: identical inputs must produce identical
  snapshot bytes (property-pinned).
- Exit codes: 0 ok, 1 reserved for breaking changes (Phase 2), 2 error.
- Domain core must not depend on CLI/filesystem/logging implementations.
- No git remote configured yet; `ci.yml` activates once hosted.

## Architecture

```
cli -> snapshot-store -> core      (dependency direction: inward)
cli -> openapi-parser -> core
```

Canonical model: `core/.../model/ContractSurface.kt`; normalization in
`Normalization.kt`; errors in `error/ContractError.kt`; deterministic
serialization in `serialization/CanonicalJson.kt`. The parser adapter is
`openapi-parser/.../OpenApiParser.kt` (swagger-parser types never leak
past it).
