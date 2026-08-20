# ContractLens

**Detect API contract changes, classify their compatibility
implications, and identify which known consumers may be affected —
before merge.**

A contract change breaks consumers at runtime, not at build time: a
DTO field removed in the backend breaks a client's request, and a raw
git diff never tells you. ContractLens captures contract snapshots,
diffs them structurally, and gives every change an explainable
verdict:

```
$ contractlens diff old.snapshot.json new.snapshot.json --classify

classification: 2 breaking, 1 non-breaking, 2 review
semver: major
  TYPE_CHANGED POST /sessions → request body → schema → properties.webhookUrl
    : string → integer [breaking] (major)
    reason: the accepted or emitted type changed
```

## Why ContractLens

- **The diff is not the question.** Renaming a field, narrowing an
  enum, or flipping required→optional is invisible in a raw git diff;
  schema differs compare schemas but never answer "who is affected".
- **Explanations you can defend.** Every verdict carries its reason
  and the exact schema path. No scores, no ML.
- **Local-first.** File-backed snapshots, no database, no network.
  The check runs the same way on a laptop and in CI.
- **One engine for every format.** OpenAPI, GraphQL SDL, and JSON
  Schema event contracts share one diff engine and one ruleset.

## Design

```
contract document
  → canonical snapshot (content-hash verified, keyed by commit)
  → structural diff (26 change kinds, precise locations)
  → classification (direction-aware rules, semver derived)
  → consumer impact mapping (declared registry, honest boundary)
  → human report / versioned JSON / DeployScore signal
```

Every format maps into one canonical model, so the engine, classifier,
and mapper are written once (ADR-001). The deep explanation is in
[docs/architecture.md](docs/architecture.md).

## Quick Start

Requires a JRE 17+. Install by downloading the release JAR (verify it
against `SHA256SUMS`), via the install scripts, via Docker, or from
source — the exact steps are in [docs/cli.md](docs/cli.md).

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
merges are blocked before anyone downstream breaks. A
[reusable GitHub Action](action.yml) runs exactly this check on PRs.

## What ContractLens Understands

- **OpenAPI 3.0 / 3.1** — the primary format; local `$ref`s resolved,
  remote and multi-file refs rejected
  ([docs/contract-model.md](docs/contract-model.md)).
- **Generated clients** — OpenAPI surfaces projected through generator
  conventions and diffed with the same engine; generated source is
  never parsed (ADR-006,
  [docs/generated-clients.md](docs/generated-clients.md)).
- **GraphQL SDL** — single-file schemas
  ([docs/graphql.md](docs/graphql.md)).
- **JSON Schema** — event contracts, core vocabulary
  ([docs/json-schema.md](docs/json-schema.md)).
- **The consumer registry** — who declares consumption of what
  ([docs/impact-analysis.md](docs/impact-analysis.md)).
- **The usage graph** — which fields consumers actually read; a
  validated format, deliberately not wired into classification yet
  ([docs/usage-graph.md](docs/usage-graph.md)).

## Compatibility Analysis

Three verdicts, always with a deterministic reason:

- **breaking** — the change can reject or invalidate consumers that
  worked before;
- **non-breaking** — existing consumers are unaffected;
- **review** — the contract evidence is insufficient for an automatic
  verdict; a human must look. `review` is not a failure state — it is
  the honest answer when the document does not carry the runtime
  behavior a rule needs.

The rules are direction-aware (request: the consumer sends, the
provider validates; response: the provider sends, the consumer reads),
conservative when the evidence runs out, and semver is **derived**
from the verdict (breaking → major, additive non-breaking → minor,
other non-breaking → patch, review → none). Renames are never
inferred: same-type add/remove pairs surface as review candidates.

The complete rule reference — every change kind, both directions, with
reasons and worked examples — is
[docs/classification.md](docs/classification.md).

## Impact Analysis

```yaml
version: 1
consumers:
  - id: example-frontend          # stable identity — must be unique
    kind: frontend                # frontend | service | sdk | generated-client | integration
    contract: example-api
    operations:                   # "*" (all) or METHOD + path-template
      - GET /users/{id}
```

`impact` maps every structural change to the registered consumers that
declare the affected operations. "Affected" means "declares
consumption of the changed surface" — never "will definitely break"
(the classifier decides breakage), and unregistered consumers are
invisible by design; the report states both. Full semantics and
validation: [docs/impact-analysis.md](docs/impact-analysis.md);
copy-ready registry: [docs/examples/example-registry.yaml](docs/examples/example-registry.yaml).

## CLI

| Command | What it does |
|---|---|
| `snapshot` | capture a contract into a snapshot |
| `snapshot verify` / `snapshot list` | verify snapshot integrity / list a store |
| `diff` | structural change set (`--classify` for verdicts) |
| `impact` | map changes to declared consumers (`--registry`) |
| `generated-diff` | diff generator-convention projections (`--style ts\|kotlin\|java`) |
| `signal` | emit the `contractlens-signal` feed payload (offline) |
| `--version` | print the version |

Exit codes: `0` success, `1` breaking changes detected, `2`
operational failure. Full reference with options, outputs, and
examples: [docs/cli.md](docs/cli.md). Machine-readable outputs are
versioned and additive-only: [docs/output-formats.md](docs/output-formats.md).

## Development

```
.\gradlew.bat build                       # compile + ktlint + ALL tests
.\gradlew.bat koverVerify                 # coverage gate (per-module minimums)
.\gradlew.bat :cli:fuzz -PfuzzIterations=5000     # seeded fuzz smoke
.\gradlew.bat :fuzz:jazzerFuzz                    # Jazzer crash-input replay
.\gradlew.bat :benchmark:benchSmoke               # benchmark smoke
.\gradlew.bat :benchmark:benchCheck               # benchmark vs baseline
```

Contributing ground rules: [CONTRIBUTING.md](CONTRIBUTING.md).
Security policy and reporting: [SECURITY.md](SECURITY.md).

## Documentation

Start at [docs/index.md](docs/index.md):

- **Concepts** — the canonical model, classification, impact analysis,
  output formats
- **Formats** — generated clients, GraphQL, JSON Schema, usage graph
- **Engineering** — testing, security, performance, fuzzing, coverage
- **Operations** — CLI, CI, release, the DeployScore feed
- **Decisions & research** — the ADRs
  ([docs/adr/](docs/adr/)) and the research records
  ([docs/research/](docs/research/)) that produced them

## Limitations

The honest list is grouped in [docs/limitations.md](docs/limitations.md):
local `$ref`s only, core-JSON-Schema vocabulary only, single-file
GraphQL, no `x-stability-level` exemptions, convention-stable (not
byte-exact) generated-client projection, the usage graph not wired
into classification, unregistered consumers invisible, contract
renames not mapped, and publication of CI/releases pending the
repository hosting decision. Each limitation states what would remove
it.

## License

MIT — see [LICENSE](LICENSE).
