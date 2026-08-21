# Release process (ADR-007)

## Artifacts

One release produces:

- `dist/contractlens-<version>/contractlens-<version>-all.jar` — the
  fat JAR (primary artifact; runs on any JRE 17+ with `java -jar`).
- `dist/contractlens-<version>/SHA256SUMS` — `sha256  filename` lines
  for every artifact in the bundle (verified before anything is
  published).
- `dist/contractlens-<version>/install.ps1`, `install.sh`,
  `uninstall.ps1` — install scripts (checksum-verified,
  PATH update opt-in).
- `dist/contractlens-<version>/CHANGELOG.md`, `LICENSE`.
- Docker image `contractlens:<version>` (secondary, CI/container use).

`dist/` is gitignored — generated artifacts are never committed.

## The local release script (verification/dogfooding)

```
powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 1.0.1
```

The script:

1. verifies the `-Version` literal against `version = "…"` in the root
   `build.gradle.kts` (strict, not a regex guess);
2. runs the full validation suite (`clean build koverVerify` — compile,
   ktlint, all tests, coverage gate);
3. builds the fat JAR and assembles the bundle;
4. writes `SHA256SUMS` and RE-VERIFIES it against the artifacts;
5. smoke-tests the artifact as an artifact: `--version`, `--help`,
   `snapshot`, `diff`, `diff --classify` (exit 1 on breaking), `impact`,
   `generated-diff`, `signal`, JSON output, and the metrics event — not
   just the source tree;
6. verifies the working tree stayed clean.

## The release workflow (publication)

`.github/workflows/release.yml` runs ONLY on `vX.Y.Z` tag pushes (never
branches, never PRs) and performs the same steps on CI: strict tag
verification against the build file, full validation, packaging,
checksum verification, artifact smoke test, tree-clean check, then
creates the GitHub Release via `gh`.

**Hosted releases (2026-08-21):** both `v1.0.0` and `v1.0.1` were
published end-to-end by tag pushes on GitHub runners — the workflow
runs the clean build + tests + coverage gate on `windows-latest`,
verifies the bundle checksums, and attaches the JAR, `SHA256SUMS`,
install scripts, CHANGELOG, and LICENSE. Each published JAR was then
downloaded from the release URL and independently verified: SHA-256
matches `SHA256SUMS` and `--version` reports the released version
(`v1.0.1` is current).

### Cutting a release

```
# bump `version` in build.gradle.kts first — the tag and the build file
# must agree or both the script and the workflow refuse
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin master
git push origin v1.0.1          # triggers release.yml
```

Tag deletion/retagging is a maintainer-only decision; automation never
creates or moves tags.

## Maintainer checklist — what to check when you push

This is the complete flow, in order. Every step below was exercised by
the first hosted releases (2026-08-21) and the repairs recorded then.

### Before any push

1. **Local gates** (the same set CI runs):
   `.\gradlew.bat build koverVerify --dependency-verification=strict`
   — compile, ktlint, all tests, the coverage gate, strict dependency
   verification. This must be green.
2. **Workflow files** if you touched `.github/`:
   `python scripts\check-workflows.py` — syntactic AND semantic
   validation. Plain YAML parsers cannot see a dangling `with:` on a
   `run:` step; this script can (it was written after exactly that
   error shipped).
3. `git diff --check`, then `git status --short` — nothing unintended
   staged; the tree is clean before the push.

### After pushing to master

4. **Watch CI** on the pushed commit: two jobs — `build-test` (matrix)
   and `verify` (sequential gates). Both green before anything else
   happens. A rapid second push cancels the first run by design
   (concurrency) — check the LATEST run.

### Before tagging (releases only)

5. **Bump the version everywhere the single source flows:**
   `build.gradle.kts` (the authoritative literal), `VersionTest.kt`,
   `SignalBuilderTest.kt`, the version examples in docs/cli.md,
   docs/release.md, docs/deployscore-feed.md, the Dockerfile comments,
   and the release-script header comment. Add a CHANGELOG entry.
   Verify locally again (step 1) — the tag↔version check in both the
   script and the workflow is strict.
6. **Tag only from a green master:** `git tag -a vX.Y.Z -m "Release vX.Y.Z"`,
   `git push origin master`, `git push origin vX.Y.Z`.
7. **Watch the tag runs:** `Release` (publishes) and the tag's own CI
   run — both must be green. Then **verify the published artifact
   user-side**: download the JAR from the release URL, check its
   SHA-256 against `SHA256SUMS`, run `--version` (see "Verifying a
   release" below).

### When Dependabot opens a PR

8. **A red PR is never merged.** The gradle group PR arrives weekly and
   will be red by design — Dependabot cannot regenerate the committed
   verification metadata, and major bumps (kotest, Jazzer) need real
   migration work. Close it, or do a **deliberate upgrade**: pick the
   bumps you want, apply them locally, regenerate the metadata
   (`gradlew --write-verification-metadata sha256` under a fresh
   `GRADLE_USER_HOME`), re-run the full local gate set (steps 1-3),
   then merge as a normal commit.
9. GitHub-actions PRs (per-action, ungrouped) can be merged when their
   CI is green — they don't touch Gradle resolution. (gradle/actions
   majors deserve a look at their release notes: v6 changed the
   caching component's licensing.)

## Verifying a release (user side)

```
# Windows PowerShell
(Get-FileHash -Algorithm SHA256 contractlens-1.0.1-all.jar).Hash.ToLowerInvariant()
Get-Content SHA256SUMS   # compare against the line for the jar

# Linux/macOS
sha256sum -c SHA256SUMS
```

## Reproducibility

Two clean-checkout builds of the fat JAR are byte-compared during the
release verification (timestamps and entry order are normalized:
`isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`).
The result of that investigation — what is byte-identical and what (if
anything) remains environment-dependent — is recorded in the delivery
report and updated here on every release. Nothing is labeled
"reproducible" without the double-build evidence.
