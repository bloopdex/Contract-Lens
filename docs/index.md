# ContractLens documentation

The repository documentation is organized by engineering concern: what
the system is, how to use it, how it was built and verified, and — in
the research records and ADRs — **why** it was built this way instead
of another way.

## Start here

- [README](../README.md) — what the tool is, quick start, installation
- [architecture.md](architecture.md) — the system end-to-end, and why it has this shape
- [cli.md](cli.md) — every command, option, and exit code
- [limitations.md](limitations.md) — what ContractLens deliberately does not claim

## Concepts

- [contract-model.md](contract-model.md) — the canonical model: identity, schemas, locations
- [classification.md](classification.md) — the breaking / non-breaking / review ruleset
- [impact-analysis.md](impact-analysis.md) — the consumer registry and mapping
- [output-formats.md](output-formats.md) — the versioned JSON formats

## Supported formats

- OpenAPI 3.0/3.1 — the primary format (see [contract-model.md](contract-model.md))
- [generated-clients.md](generated-clients.md) — generator-convention projection diffing
- [graphql.md](graphql.md) — the GraphQL SDL adapter
- [json-schema.md](json-schema.md) — the JSON Schema event adapter
- [usage-graph.md](usage-graph.md) — field-level usage records (an integration boundary)

## Engineering

- [testing.md](testing.md) — the testing strategy and layers
- [security.md](security.md) — threat → boundary → defense → verification
- [performance.md](performance.md) — the benchmark record and comparison policy
- [fuzzing.md](fuzzing.md) — the seeded harness and Jazzer targets
- [coverage.md](coverage.md) — the Kover gate and its rationale

## Operations

- [ci.md](ci.md) — the CI architecture, failure semantics, local equivalents
- [release.md](release.md) — the release process and reproducibility
- [deployscore-feed.md](deployscore-feed.md) — the `contractlens-signal` v1 contract
- [examples/example-registry.yaml](examples/example-registry.yaml) — copy-ready registry template

## Decisions and research

- [adr/INDEX.md](adr/INDEX.md) — every architectural decision, numbered and dated
- [research/](research/README.md) — the research records: the questions,
  the alternatives that were considered, and the evidence behind the
  decisions above

## Limitations

[limitations.md](limitations.md) groups every known boundary by area,
with what is missing, why, what ContractLens does instead, and what
would remove the limitation.
