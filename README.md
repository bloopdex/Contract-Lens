# ContractLens

**Contract change detection with compatibility classification and
consumer impact analysis — before merge.**

```
contractlens diff old.snapshot.json new.snapshot.json --classify

classification: 2 breaking, 1 non-breaking, 2 review
semver: major
  TYPE_CHANGED POST /sessions → request body → schema → properties.webhookUrl
    : string → integer [breaking] (major)
    reason: the accepted or emitted type changed
```

A contract change breaks consumers at runtime, not at build time: a DTO
field removed in the backend breaks a client's request, and a raw git
diff never tells you. ContractLens captures contract snapshots, diffs
them structurally, classifies every change as breaking / non-breaking /
review with an explainable reason, and maps the result onto the
consumers you have declared — locally, deterministically, before you
merge.

## Why ContractLens

- **The diff is not the question.** Renaming a field, narrowing an
  enum, or flipping required→optional is invisible in a raw git diff;
  generic differs (oasdiff, openapi-diff) compare schemas but never
  answer "who is affected".
- **Explanations you can defend.** Every verdict carries its reason and
  the exact location. No scores, no ML, no surprises.
- **Local-first.** File-backed snapshots, no database, no network, no
  telemetry. The check runs the same way on a laptop and in CI.
- **The same engine for every format.** OpenAPI, GraphQL SDL, and JSON
  Schema event contracts share one diff engine and one ruleset.

## How it works

```
contract document
  → canonical snapshot (content-hash verified, keyed by commit)
  → structural diff (26 change kinds, precise locations)
  → classification (direction-aware rules, semver derived)
  → consumer impact mapping (declared registry, honest boundary)
  → human report / versioned JSON / DeployScore signal
```

The full pipeline and the design rationale are in
[docs/architecture.md](docs/architecture.md).

## Supported contract types

- **OpenAPI 3.0 / 3.1** — the primary format; local `$ref`s resolved,
  remote and multi-file refs rejected.
- **GraphQL SDL** — single-file schemas; fields and types map onto the
  canonical model.
- **JSON Schema** — event contracts (core vocabulary).
- **Generated-client projections** — OpenAPI surfaces projected
  through generator conventions and diffed with the same engine.

Each format is an adapter over one canonical model — see
[Supported formats](#supported-formats).

## Core concepts

- **Snapshot** — a canonical, content-hash-verified capture of one
  contract at one commit (`<contract>@<sha>.snapshot.json`).
- **Change set** — structural facts only: what changed, where, from
  what, to what. Verdicts are deliberately absent until you ask for
  classification.
- **Canonical identity** — operations are `(method, normalized path
  template)`; `/users/{id}` and `/users/{userId}` are the same
  operation, and renames of template variables are non-breaking.
- **Registry** — the explicit, versioned, local list of consumers you
  declare. Unregistered consumers are invisible to ContractLens, and
  every report says so.

## Installation

Requires a JRE 17+.

**Release JAR** (the primary artifact):

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

## Quick start

```
# capture a contract into a snapshot (commit SHA from git HEAD)
contractlens snapshot api/openapi.yaml --store .contractlens/snapshots

# after the contract changes:
contractlens snapshot api/openapi.yaml --store .contractlens/snapshots

# what changed, and what it means:
contractlens diff <old>.snapshot.json <new>.snapshot.json --classify

# who declares consumption of the changed operations:
contractlens impact <old>.snapshot.json <new>.snapshot.json --registry registry.yaml
```

Exit code `1` means breaking changes — wire the check into CI and
merges are blocked before anyone downstream breaks.

## Commands

| Command | What it does |
|---|---|
| `snapshot` | capture a contract into a snapshot |
| `snapshot verify` | verify snapshot integrity (never bypassed) |
| `snapshot list` | list snapshots in a store |
| `diff` | structural change set (`--classify` for verdicts, `--json` for machine output) |
| `impact` | map changes to declared consumers (`--registry`) |
| `generated-diff` | diff generator-convention projections (`--style ts\|kotlin\|java`) |
| `signal` | emit the DeployScore feed payload (offline) |
| `--version` | print the version |

Full reference with options, outputs, and examples:
[docs/cli.md](docs/cli.md).

## Classification

Three verdicts, always with a deterministic reason:

- **breaking** — the change can reject or invalidate consumers that
  worked before.
- **non-breaking** — existing consumers are unaffected.
- **review** — the contract evidence is insufficient for an automatic
  verdict; a human must look.

The rules are direction-aware (request: the consumer sends, the
provider validates; response: the provider sends, the consumer reads),
conservative when the evidence runs out, and semver labels are derived
from verdicts (breaking→major, additive non-breaking→minor, other
non-breaking→patch, review→none). Renames are never inferred: same-type
property add/remove pairs are surfaced as review candidates.

The complete rule reference — every change kind, both directions, with
reasons — is in [docs/classification.md](docs/classification.md).

## Impact analysis

A consumer registry declares who consumes what:

```yaml
version: 1
consumers:
  - id: example-frontend          # stable identity — must be unique
    kind: frontend                # frontend | service | sdk | generated-client | integration
    contract: example-api
    operations:                   # "*" (all) or METHOD + path-template
      - GET /users/{id}
    contact: frontend team        # optional
    notes: optional free-form     # optional
```

Copy-ready template: [docs/examples/example-registry.yaml](docs/examples/example-registry.yaml).

`impact` maps every structural change to the registered consumers that
declare the affected operations. "Affected" means "declares consumption
of the changed surface" — never "will definitely break" (the classifier
decides breakage), and unregistered consumers are invisible by design;
the report states both.

## Generated clients

`generated-diff` projects both snapshots through deterministic
generator conventions (method names like `getUsersById` from
`GET /users/{id}`, merged request objects, normalized return types) and
diffs the projections with the shared engine. Generated client source
is **not** parsed — the projection models what generators do, so the
comparison is convention-stable rather than byte-exact (documented
limitation, ADR-006).

## Supported formats

### GraphQL

Single-file SDL (`.graphql`/`.graphqls` are auto-detected by
`snapshot`). Query and mutation fields become operations; object types
become schemas. The adapter is groundwork: it covers the core type
system, and its output feeds the same diff/classification engine as
everything else.

### JSON Schema

`contractlens snapshot event.json --format json-schema` maps core
vocabulary (type, properties, required, items, enum, constraints,
nullability) onto the canonical model. Local refs stay as ref nodes;
cross-document refs are rejected loudly.

### Usage graph

A validated format recording which fields a consumer actually reads —
the substrate for usage-aware classification. It is **not wired into
classification**: that requires real usage data (a producer recording
field reads), which does not exist yet. Until one does, classification
runs against the whole contract, and the graph is a documented
integration boundary.

## Output formats

Human-readable reports on stdout; versioned, additive-only JSON via
`--json` — see [docs/output-formats.md](docs/output-formats.md) for
`contractlens-diff` v1/v2, `contractlens-impact` v2,
`contractlens-generated-diff` v1, and `contractlens-signal` v1.

## Exit codes

| 0 | successful analysis, no breaking changes (or no classification requested) |
|---|---|
| 1 | breaking changes detected |
| 2 | operational failure (usage, input, IO, corrupt snapshots) |

## Security

Local-first with a reviewed security posture: 10 MB input limits before
parsing, nesting-depth guards, local-only `$ref`s, snapshot-store path
sanitization, redaction by construction (descriptions/examples never
enter the model), typed failures on every boundary, SHA-256-verified
dependencies, and an OSV-based vulnerability policy with documented
waivers. See [docs/security.md](docs/security.md) and
[SECURITY.md](SECURITY.md).

## Performance

Deterministic benchmark scenarios with a committed baseline and a
nightly comparison policy (a regression must be both 3× the baseline
and above 1 s to fail; nothing is rewritten automatically). The
recorded numbers — and what they do and do not mean — are in
[docs/benchmarks.md](docs/benchmarks.md).

## Development

```
.\gradlew.bat build                       # compile + ktlint + ALL tests
.\gradlew.bat ktlintFormat                # auto-format
.\gradlew.bat koverVerify                 # coverage gate (per-module minimums)
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000          # seeded fuzz smoke
.\gradlew.bat :fuzz:jazzerFuzz                        # Jazzer crash replay
.\gradlew.bat :fuzz:jazzerFuzz -Pjazzer.fuzz=1        # Jazzer fuzzing (2m/target)
.\gradlew.bat :benchmark:benchSmoke                   # benchmark smoke
.\gradlew.bat :benchmark:benchCheck                   # benchmark vs baseline
```

CI architecture, failure policy, and the local equivalent of every CI
step: [docs/ci.md](docs/ci.md). Release process and checksum
verification: [docs/release.md](docs/release.md). Fuzzing layers:
[docs/fuzzing.md](docs/fuzzing.md).

## Documentation

- [docs/architecture.md](docs/architecture.md) — pipeline, canonical model, adapters, design principles
- [docs/classification.md](docs/classification.md) — the compatibility rule reference
- [docs/cli.md](docs/cli.md) — command reference and exit codes
- [docs/output-formats.md](docs/output-formats.md) — versioned JSON formats
- [docs/deployscore-feed.md](docs/deployscore-feed.md) — the DeployScore signal contract
- [docs/security.md](docs/security.md) / [SECURITY.md](SECURITY.md) — security review and policy
- [docs/benchmarks.md](docs/benchmarks.md) — methodology and recorded baseline
- [docs/coverage.md](docs/coverage.md) — coverage policy and gate
- [docs/ci.md](docs/ci.md) / [docs/release.md](docs/release.md) / [docs/fuzzing.md](docs/fuzzing.md)
- [docs/adr/INDEX.md](docs/adr/INDEX.md) — architectural decision records
- [CONTRIBUTING.md](CONTRIBUTING.md) / [CHANGELOG.md](CHANGELOG.md)

## Limitations

- Local `$ref`s only; multi-file and remote references are rejected
  (`UNSUPPORTED_REFERENCE`).
- Inputs are bounded by `MAX_INPUT_BYTES` (10 MB) and per-adapter depth
  guards.
- `x-stability-level` exemptions are not implemented — the canonical
  model carries no stability levels.
- The usage graph is validated but not wired into classification (no
  real usage producers exist — see above).
- The generated-client projection is convention-stable, not byte-exact;
  model type names are not reported (`$ref`s resolve inline).
- `impact` requires both snapshots to share one contract name
  (`CONTRACT_MISMATCH` otherwise); contract renames are not mapped.
- Strict OpenAPI validation can refuse real-world documents with
  undeclared path parameters — the correct loud failure; the fix
  belongs to the document's source.
- CI workflows and release publication execute once the repository is
  hosted; every step is verified locally (documented in
  [docs/ci.md](docs/ci.md)).
- The DeployScore feed is a producer-side contract; the receiving
  service does not exist yet (ADR-008).

## License

MIT — see [LICENSE](LICENSE).
