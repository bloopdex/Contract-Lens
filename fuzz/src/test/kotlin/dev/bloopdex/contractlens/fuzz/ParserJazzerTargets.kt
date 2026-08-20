// Phase 6 coverage-guided fuzz targets (Jazzer).
//
// Target selection is deliberate, not exhaustive (per the Phase 6
// execution principles: select meaningful entry points, never blindly
// expose every function):
//
//   - the five PARSERS are the untrusted-input boundaries — exactly
//     where coverage-guided exploration adds value over the Phase 5
//     seeded harness.
//   - snapshot-build-verify round-trips the canonical model through
//     build → hash → serialize → parse for every input that manages
//     to parse (GraphQL documents, the string-based boundary with the
//     richest grammar).
//
// NOT targeted: the diff engine, classifier, and mapper. Their inputs
// are typed, already-validated domain data (surfaces, change sets),
// not bytes — the Phase 5 invariant fuzz (200k classifier iterations)
// already covers them, and a Jazzer target that must construct valid
// typed inputs out of bytes would only fuzz our own test scaffolding.
// That evaluation is recorded in docs/fuzzing.md.
//
// Every target is bounded (maxDuration), writes only to ONE reused
// temp file per boundary (no uncontrolled filesystem writes), makes
// no network calls, and preserves the parsers' expected failure
// semantics (typed ContractError is a NORMAL outcome — only an
// untyped exception or a JVM crash is a finding).

package dev.bloopdex.contractlens.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.graphql.GraphQlParser
import dev.bloopdex.contractlens.jsonschema.JsonSchemaParser
import dev.bloopdex.contractlens.openapi.OpenApiParser
import dev.bloopdex.contractlens.registry.RegistryParser
import dev.bloopdex.contractlens.registry.UsageParser
import dev.bloopdex.contractlens.snapshot.buildSnapshot
import dev.bloopdex.contractlens.snapshot.parseAndVerifySnapshot
import dev.bloopdex.contractlens.snapshot.snapshotJsonBytes
import java.nio.file.Files
import java.nio.file.Path

class ParserJazzerTargets {
    companion object {
        private val tempDir = Files.createTempDirectory("contractlens-jazzer")
        private val openApiInput: Path = tempDir.resolve("openapi-input.yaml")

        private val openApi = OpenApiParser()
        private val graphQl = GraphQlParser()
        private val jsonSchema = JsonSchemaParser()
    }

    @FuzzTest(maxDuration = "2m")
    fun fuzzOpenApi(data: FuzzedDataProvider) {
        Files.write(openApiInput, data.consumeRemainingAsBytes())
        try {
            openApi.parse(openApiInput, "jazzer-openapi")
        } catch (_: ContractError) {
            // expected failure semantics — typed errors are normal outcomes
        }
    }

    @FuzzTest(maxDuration = "2m")
    fun fuzzGraphQl(data: FuzzedDataProvider) {
        try {
            graphQl.parse(data.consumeRemainingAsString(), "jazzer-graphql")
        } catch (_: ContractError) {
        }
    }

    @FuzzTest(maxDuration = "2m")
    fun fuzzJsonSchema(data: FuzzedDataProvider) {
        try {
            jsonSchema.parse(data.consumeRemainingAsString(), "jazzer-json-schema")
        } catch (_: ContractError) {
        }
    }

    @FuzzTest(maxDuration = "2m")
    fun fuzzRegistry(data: FuzzedDataProvider) {
        try {
            RegistryParser.parse(data.consumeRemainingAsString(), "jazzer-registry.yaml")
        } catch (_: ContractError) {
        }
    }

    @FuzzTest(maxDuration = "2m")
    fun fuzzUsageGraph(data: FuzzedDataProvider) {
        try {
            UsageParser.parse(data.consumeRemainingAsString(), "jazzer-usage.yaml")
        } catch (_: ContractError) {
        }
    }

    @FuzzTest(maxDuration = "2m")
    fun fuzzSnapshotRoundTrip(data: FuzzedDataProvider) {
        val text = data.consumeRemainingAsString()
        try {
            val surface = graphQl.parse(text, "jazzer-roundtrip")
            val document =
                buildSnapshot(
                    contract = "jazzer-roundtrip",
                    sourcePath = null,
                    identity =
                        dev.bloopdex.contractlens.snapshot.SnapshotIdentity(
                            kind = "git-commit",
                            sha = "f".repeat(40),
                        ),
                    capturedAt = "2026-08-19T00:00:00Z",
                    surface = surface,
                )
            val bytes = snapshotJsonBytes(document)
            val parsed = parseAndVerifySnapshot(bytes, "jazzer-roundtrip.snapshot.json")
            check(parsed.surface == surface) { "snapshot round-trip changed the surface" }
        } catch (_: ContractError) {
            // the GraphQL parse itself may fail — that is a normal outcome
        }
    }
}
