# Impact analysis — the consumer registry and mapping

The tool's reason to exist: a schema diff answers "what changed"; the
registry answers "**who declares consumption of what changed**". This
page documents the registry format, its validation, and how changes map
to consumers (ADR-002).

## The registry

A versioned YAML file, hand-edited, supplied per run with
`--registry`:

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

Copy-ready template: [examples/example-registry.yaml](examples/example-registry.yaml).

Field semantics:

- `id` — the stable consumer identity, unique across the registry.
- `kind` — what the consumer is. The kind exists because the *same*
  structural change has different severity per consumer type (the Buf
  evidence recorded in the research: generated-source consumers depend
  on different things than wire-format consumers). Today the kind is
  recorded and reported; it does not yet change verdicts.
- `contract` — which contract this consumer consumes.
- `operations` — canonical operation selectors: `"*"` or
  `"METHOD /path-template"`. Selectors use the canonical identity, so
  `GET /users/{id}` and `GET /users/{userId}` select the same
  operation.
- `contact`, `notes` — optional human metadata, reported but never
  classified on.

## Validation

The registry is untrusted input, validated strictly on ingest
(kaml strict mode in the `:registry` adapter):

- unsupported `version` → `REGISTRY_VERSION_UNSUPPORTED`;
- duplicate ids → `REGISTRY_DUPLICATE_ID` (ids are the identity);
- blank id/contract, unknown kind, empty operations → typed errors;
- malformed selectors → `REGISTRY_SELECTOR_INVALID`;
- unknown YAML fields → rejected (never silently reinterpreted).

A consumer whose `contract` is not the diffed contract is **not an
error** — it is simply not affected by this diff.

## Mapping

`contractlens impact <old> <new> --registry <file>` runs the mapping:

1. each change's location determines its owning contract and affected
   operation (via the engine's pinned location grammar);
2. registry entries referencing that contract are tested against the
   consumer's selectors by canonical identity;
3. the report groups changes per consumer per operation, with mapping
   reasons; changes no registered consumer declares stay visible in an
   unmapped section.

Determinism: equivalent selectors dedupe to the first occurrence;
overlapping selectors never produce duplicate impact records; consumer
order never changes the report (property-pinned).

## The honesty boundary

**"Affected" means "declares consumption of the changed surface" —
never "will definitely break".** The classifier decides breakage; the
mapper decides who might care. Two boundaries are stated in every
report:

- *unregistered consumers are invisible* — a documented false-negative
  boundary, not a silent gap;
- *no confidence tiers* — "likely affected" vs "confirmed affected"
  requires usage data, which does not exist yet (see
  [usage-graph.md](usage-graph.md)).

The report text states the checked count explicitly ("N registered
consumers checked; unregistered consumers are not known to
ContractLens").

## Scope of one `impact` run

`impact` compares two snapshots of the **same contract** — evolution,
not cross-contract comparison. Different contract names fail with
`CONTRACT_MISMATCH`; contract renames are therefore not mapped (a
documented limitation, [limitations.md](limitations.md)).

## JSON output

`--json` emits the versioned `contractlens-impact` v2 document
(complete change set, per-consumer impacts with reasons, the honesty
note) — see [output-formats.md](output-formats.md).
