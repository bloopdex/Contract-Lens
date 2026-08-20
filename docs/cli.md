# CLI reference

`contractlens` is the single executable (Clikt-based, JVM). Every
command writes its result to stdout; diagnostics, structured logs, and
the analysis metrics go to stderr. Exit codes are part of the contract
(see below).

Global: `--version` prints the version from the build's single
authoritative source. `-v/--verbose` raises structured logging to debug
level (JSON events on stderr).

## Installation

Requires a JRE 17+.

**Release JAR** (the primary artifact, ADR-007):

```
# download contractlens-<version>-all.jar and SHA256SUMS from the release
# verify first:
powershell "(Get-FileHash -Algorithm SHA256 contractlens-0.1.0-all.jar).Hash.ToLowerInvariant()"
# compare against the SHA256SUMS line, then:
java -jar contractlens-0.1.0-all.jar --version
```

**Install scripts** (from the release bundle): `install.ps1`
(Windows), `install.sh` (Linux/macOS) — install the JAR plus a shim,
verify the checksum first, PATH update is opt-in. `uninstall.ps1`
reverses it.

**Docker**:

```
docker build -t contractlens:0.1.0 .
docker run --rm -v "$PWD:/work" -w /work contractlens:0.1.0 diff \
    old.snapshot.json new.snapshot.json --classify
```

**From source**:

```
.\gradlew.bat :cli:installDist
cli\build\install\contractlens\bin\contractlens.bat --version
```

The release process behind the artifacts (checksums, reproducibility,
tag-driven publication): [release.md](release.md).

## Commands

| Command | Purpose |
|---|---|
| [`snapshot`](#snapshot) | capture a contract document into a snapshot |
| [`verify`](#verify) | verify snapshot file(s) integrity |
| [`list`](#list) | list snapshots in a store |
| [`diff`](#diff) | structural change set between two snapshots |
| [`impact`](#impact) | map changes to declared consumers |
| [`generated-diff`](#generated-diff) | diff generator-convention projections |
| [`signal`](#signal) | emit the DeployScore feed payload |

## snapshot

```
contractlens snapshot <contract> [--name NAME] [--sha SHA] [--store DIR]
                      [--format openapi|graphql|json-schema] [--json]
```

- Input: an OpenAPI 3.0/3.1, GraphQL SDL (`.graphql`/`.graphqls`,
  auto-detected), or JSON Schema document (`--format json-schema`).
- Output: the snapshot file path (stdout), or a `--json` summary
  (contract, sha, operation count).
- `--sha` defaults to `git rev-parse HEAD` in the current directory;
  `--name` defaults to the file stem; `--store` defaults to
  `.contractlens/snapshots`.
- Oversized inputs (> 10 MB) fail with `INPUT_TOO_LARGE`; malformed
  documents fail with typed errors — parsing never partially succeeds.

```
contractlens snapshot api/openapi.yaml --store .contractlens/snapshots
contractlens snapshot event.json --format json-schema
```

## verify

```
contractlens snapshot verify <snapshot-file> [<snapshot-file> ...]
```

Verifies each snapshot's format and **content hash**; corrupted or
tampered snapshots are refused loudly (exit 2, `SNAPSHOT_INTEGRITY`).

## list

```
contractlens snapshot list [--store DIR]
```

Lists the snapshots in a store directory (contract, commit SHA, format).

## diff

```
contractlens diff <old-snapshot> <new-snapshot> [--classify] [--json]
```

- Input: two verified snapshot files.
- Output: the deterministic structural change set — human format
  (summary counts + one line per change with its location), or
  `contractlens-diff` v1 JSON.
- `--classify` attaches the verdicts, reasons, and semver labels
  (human format or `contractlens-diff` v2 JSON) and sets exit 1 when any
  change is breaking.
- Renames are never inferred; verdicts are absent without `--classify`.

```
contractlens diff old.snapshot.json new.snapshot.json
contractlens diff old.snapshot.json new.snapshot.json --classify --json
```

## impact

```
contractlens impact <old-snapshot> <new-snapshot> --registry FILE [--json]
```

- Input: two verified snapshots of the **same contract** (evolution,
  not comparison — different names fail with `CONTRACT_MISMATCH`) and a
  consumer registry.
- Output: the change set mapped to declared consumers — per-consumer
  grouping with mapping reasons, unmapped changes kept visible, plus
  the honesty note ("unregistered consumers are not visible to
  ContractLens"). `--json` emits `contractlens-impact` v2.
- Exit 1 when any change is breaking.

```
contractlens impact old.snapshot.json new.snapshot.json --registry registry.yaml
```

## generated-diff

```
contractlens generated-diff <old-snapshot> <new-snapshot>
                      --style ts|kotlin|java [--classify] [--json]
```

- Projects both OpenAPI snapshots through deterministic generator
  conventions (ADR-006) — client method names, merged request objects,
  normalized return types — and diffs the projections with the shared
  engine. Generated client **source is never parsed** (see
  [architecture.md](architecture.md)).
- `--classify` adds verdicts and exit 1 on breaking; `--json` emits
  `contractlens-generated-diff` v1.

```
contractlens generated-diff old.snapshot.json new.snapshot.json --style ts --classify
```

## signal

```
contractlens signal <old-snapshot> <new-snapshot> [--registry FILE] [--output FILE]
```

- Runs the full analysis and emits the `contractlens-signal` v1
  DeployScore feed payload (metadata only — counts, verdicts, semver,
  operation identities, consumer ids; see
  [deployscore-feed.md](deployscore-feed.md)) to stdout or `--output`.
- Offline by construction: there is no network path. An unwritable
  output fails with `OUTPUT_UNWRITABLE` (exit 2). Exit 1 when breaking
  changes are present.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | successful analysis, no breaking changes (or no classification requested) |
| 1 | breaking changes detected — `diff --classify`, `impact`, `generated-diff --classify`, `signal` |
| 2 | operational failure: bad usage, missing/unreadable files, malformed input, corrupt snapshots |

Code 1 is reserved for breaking changes — usage errors are remapped to
2, and the CLI never exits 1 for anything else.
