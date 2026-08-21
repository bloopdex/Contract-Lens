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

**First hosted release (2026-08-21):** the `v1.0.0` tag push published
the release end-to-end on GitHub runners — the workflow ran the clean
build + tests + coverage gate on `windows-latest`, verified the
bundle checksums, and attached the JAR, `SHA256SUMS`, install scripts,
CHANGELOG, and LICENSE. The published JAR was then downloaded from the
release URL and independently verified: SHA-256 matches `SHA256SUMS`
and `--version` reports `1.0.0`.

### Cutting a release

```
# bump `version` in build.gradle.kts first — the tag and the build file
# must agree or both the script and the workflow refuse
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin master
git push origin v1.0.1          # triggers release.yml once hosted
```

Tag deletion/retagging is a maintainer-only decision; automation never
creates or moves tags.

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
