# Architecture

This page explains the architecture to someone who has never seen the
source code: the problem it exists for, the goal it must guarantee, and
why each layer has the shape it has. The canonical model's details live
in [contract-model.md](contract-model.md); the rules in
[classification.md](classification.md).

## Problem

A contract change breaks consumers at runtime, not build time. Each
consumer compiles against its own copy of the contract (a DTO, a
generated client, a hand-written API layer), so a field removed in the
provider breaks a client's request with nothing in the raw git diff to
warn anyone. Pure schema diffing is a solved problem elsewhere; what
was missing is a tool that also says *which declared consumers the
change hits* and *why it matters*, before merge.

## Architectural goal

ContractLens must guarantee: **given two captures of one contract and
the declared consumers, it produces a deterministic, explainable answer
to "what changed, does it break consumers, and who declares consumption
of the changed surface"** — with every verdict carrying its reason and
its exact schema path, and every boundary failing loudly instead of
guessing.

Three properties follow from the goal:

- **Determinism** — identical inputs produce identical outputs, so the
  answer is reproducible in CI and reviewable by humans.
- **Explainability** — no scores, no ML: every change carries its kind,
  its location, and its reason.
- **Honesty** — where the contract evidence is insufficient, the
  answer is `review`, not a guess; where knowledge is declared (the
  registry), its boundary is stated in every report.

## Canonical model — one model for every format

The central decision (ADR-001, ADR-004): every input format maps into
**one** format-neutral contract surface, so the diff engine, the
classifier, and the mapper are written exactly once. Without it,
OpenAPI's vendor shapes, GraphQL's type system, and JSON Schema's
keyword vocabulary would each need their own diff and their own rules —
three rule sets that would inevitably disagree.

The model carries structural concepts only: operations (with canonical
path-template identity), parameters, request bodies, responses, and
recursive schema nodes (types, properties, required, enums,
constraints, nullability, refs). Descriptions, examples, and other
prose are deliberately **not captured** — they carry no compatibility
information, they are where secrets live, and their absence is the
redaction boundary. Full detail: [contract-model.md](contract-model.md).

## Pipeline

```
source (OpenAPI / GraphQL SDL / JSON Schema)
  → per-format parser adapter → canonical surface
  → snapshot (content-hash verified, keyed by contract + commit SHA)
  → structural diff (26 change kinds, precise locations, no verdicts)
  → classification (direction-aware rules, derived semver)
  → consumer impact mapping (declared registry, honesty boundary)
  → human report · versioned JSON · DeployScore signal
```

Two stage-boundary rules keep the pipeline composable:

1. **Every stage is a pure function of its inputs** except the parsers
   and the file-backed store.
2. **No stage knows about later stages.** The diff engine does not know
   consumers exist; the classifier does not know which consumers read
   what; the mapper does not decide breakage. Each layer can be tested
   and reasoned about alone.

## Adapters — what belongs to each format

An adapter's job is exactly one translation: a format's syntax into
the canonical model, plus the format's own validation. Everything else
is shared.

| Adapter | Module | Scope |
|---|---|---|
| OpenAPI 3.0/3.1 | `:openapi-parser` | swagger-parser under the hood; the version is validated *before* parsing (Swagger 2.0 would otherwise be silently converted); local `$ref`s resolved with cycle/depth guards; remote refs rejected |
| GraphQL SDL | `:graphql` | graphql-java schema parser; query/mutation fields → operations, types → schemas ([graphql.md](graphql.md)) |
| JSON Schema | `:json-schema` | core vocabulary → model; local refs stay REF nodes, cross-document refs rejected ([json-schema.md](json-schema.md)) |
| Consumer registry | `:registry` | versioned YAML via kaml, strict validation ([impact-analysis.md](impact-analysis.md)) |
| Usage graph | `:registry` | same boundary; records field reads ([usage-graph.md](usage-graph.md)) |
| Generated-client projection | `:generated-client` | not a parser — a projection of OpenAPI surfaces into generator-convention shape ([generated-clients.md](generated-clients.md)) |

## Core engine — what must stay format-independent

`:core` holds everything that reasons about the model: the diff engine,
the classifier, the consumer mapper, the registry/usage domain, the
signal builder, the error model. Two rules keep the core a core:

- **Dependency direction is strictly inward** — every adapter depends
  on `:core` only; `:cli` depends on all of them; nothing in `:core`
  depends on an adapter, a filesystem, or a CLI.
- **The parser is a facade, not the model** — swagger-core types never
  leak past the OpenAPI adapter (ADR-005).

## Snapshots

A snapshot is the canonical serialization of one contract at one commit
(ADR-003): contract + surface + identity (git-commit SHA) + timestamp +
content hash over everything except the timestamp. Loading always
re-verifies the hash, so a corrupted or hand-edited snapshot is refused
loudly and never diffed. Snapshots are files
(`<contract>@<sha>.snapshot.json`) in a plain directory; the index is a
directory scan rebuilt on every start, and contract names are
sanitized so they can never escape the store.

Snapshots exist because a diff needs two trusted captures of one
contract — and because tamper-evidence is a property, not a convention.

## The diff engine

`DiffEngine.diff(old, new)` produces a deterministic change set over
the canonical identities (ADR-001): operations match by
`(method, path identity)`, parameters by `(in, name)`, responses by
normalized status, schemas traverse recursively to leaf locations. The
26 change kinds are **structural facts** — `PROPERTY_REMOVED` says a
property disappeared, never whether that breaks anyone. Two
conservative choices are deliberate:

- **Renames are never inferred** — a removed field and an added field
  are two facts; the classifier may pair same-type add/remove pairs as
  rename *candidates*.
- **Verdicts are null in the engine's output** — compatibility is a
  separate concern; the engine's JSON stays free of judgment.

## The classifier

A separate layer over the change set (ADR-001). Direction-aware rules
(what the consumer sends vs. what the provider sends) attach a verdict
and a deterministic reason to every change and derive a semver label.
When the location grammar cannot determine a direction — or the rule's
evidence is insufficient — the verdict is `review`. The full rule
reference: [classification.md](classification.md).

## Consumer registry and impact mapping

The registry is the **declared** consumer knowledge (ADR-002): a
versioned YAML file of consumers with canonical operation selectors.
`ConsumerMapper` joins the change set against it: a change maps to
every consumer whose selector covers the change's operation. The
honesty boundary is architectural, not cosmetic: "affected" means
"declares consumption of the changed surface", unregistered consumers
are invisible by design, and every report states both. Detail:
[impact-analysis.md](impact-analysis.md).

## Usage graph — where the architecture stops

The usage graph records which fields a consumer *actually reads*, per
operation and direction — the substrate for future usage-aware
classification. It is implemented (format, validation, deterministic
merging) and **deliberately not wired into classification**: that
requires real usage data, and no producer of such data exists. Wiring
against an empty dataset would be an untested heuristic that violates
the deterministic ruleset contract. The boundary is documented in
[usage-graph.md](usage-graph.md); the revisit condition is recorded in
ADR-001.

## Generated-client projection — why projection exists

Parsing generated client source was rejected (cross-language fragility,
generator version noise — ADR-006). Instead, ContractLens **projects
the contract the way a generator would** (method names, merged request
objects, normalized return types) and diffs the projections with the
shared engine. The projection is convention-stable, not byte-exact —
an honest model of generators, not a generator.

## Determinism

Determinism is a requirement, not a feature: identical inputs produce
byte-identical snapshots, change sets, reports, and signal payloads
(the two documented exceptions are the variable-metadata fields
`capturedAt` and `analyzedAt`). It is enforced structurally
(canonicalization at construction, sorted collections, stable error
codes) and pinned by property tests and fuzz sweeps. Determinism
matters because the output is evidence: a CI gate and a human review
must both see the same answer.

## Explainability

Every change answers What / Where / From / To / Why-it-matters: kind,
precise location (document-pointer path), from/to summaries, and the
rule's reason. Locations exist so reports quote the schema path, never
an internal id. Explainability is what makes `review` verdicts
actionable and what lets a developer defend a verdict in a merge
review.

## Failure boundaries

Untrusted and malformed data is contained at every boundary:

- **Inputs** — size-bounded (10 MB) before parsing, depth-guarded,
  local-refs-only, typed failures (`INPUT_TOO_LARGE`,
  `UNSUPPORTED_REFERENCE`, `DEPTH_EXCEEDED`, …); third-party exceptions
  never reach the CLI.
- **Storage** — content-hash verification on every load; corrupt
  snapshots are refused, never silently degraded; path escape is
  pinned by a regression test.
- **Outputs** — redaction by construction (the model never holds
  prose); stdout carries command output only; logs go to stderr.
- **Exit codes** — 0 success, 1 breaking changes, 2 operational
  failure; code 1 is reserved and never repurposed.

The full threat → boundary → defense → verification map:
[security.md](security.md).

## Architectural constraints — what it deliberately does not do

- **No network code path.** Local-first is a boundary, not a
  preference: no remote refs, no servers, no telemetry (ADR-003,
  ADR-008).
- **No database.** Files are the storage.
- **No ML or scoring.** Deterministic rules only.
- **No generated-source parsing.** Projection, not parsing (ADR-006).
- **No automatic fixing.** The tool explains; it never edits
  contracts.
- **No usage-aware classification yet** — the recorded deferral above.
- **No confidence tiers in impact mapping** — declared knowledge only.

These constraints are what keep the tool small enough to trust: every
capability above is a deliberate addition with a recorded decision;
everything not here is a deliberate boundary with a recorded revisit
condition.

## Design principles

1. **Explainability over black-box scores** — every verdict carries its
   reason; every report shows the location.
2. **Determinism over ML** — identical inputs produce identical
   outputs, test- and fuzz-pinned.
3. **Conservative classification** — insufficient evidence means
   `review`, never a silent guess.
4. **Loud failures** — corrupt, malformed, or oversized inputs fail
   with typed errors, never silently degrade.
5. **Local-first** — file-backed storage, no network code path.
