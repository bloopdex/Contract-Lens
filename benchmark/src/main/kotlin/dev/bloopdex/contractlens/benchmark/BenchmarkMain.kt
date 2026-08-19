// Phase 5 performance baselines (methodology: docs/benchmarks.md).
//
// Each scenario: warmup runs, then timed runs; the recorded value is
// the MEDIAN (min also reported). Deterministic synthetic fixtures so
// the baseline is reproducible: a 5k-line OpenAPI spec, its mutated
// twin, a 1k-consumer registry, a 1k-line GraphQL SDL document, and a
// 1k-line JSON Schema document. Results are printed and written to
// docs/benchmarks/baseline.json. No targets are claimed — Phase 6
// turns these into regression benchmarks.

package dev.bloopdex.contractlens.benchmark

import dev.bloopdex.contractlens.core.classify.Classifier
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.impact.ConsumerMapper
import dev.bloopdex.contractlens.core.registry.Consumer
import dev.bloopdex.contractlens.core.registry.ConsumerKind
import dev.bloopdex.contractlens.core.registry.ConsumerRegistry
import dev.bloopdex.contractlens.core.registry.parseSelector
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.generated.GeneratedClientProjection
import dev.bloopdex.contractlens.generated.GeneratorStyle
import dev.bloopdex.contractlens.graphql.GraphQlParser
import dev.bloopdex.contractlens.jsonschema.JsonSchemaParser
import dev.bloopdex.contractlens.openapi.OpenApiParser
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import kotlin.system.measureNanoTime

@Serializable
data class BenchmarkResult(
    val scenario: String,
    val inputSize: String,
    val warmupRuns: Int,
    val timedRuns: Int,
    val medianMs: Double,
    val minMs: Double,
)

@Serializable
data class BenchmarkBaseline(
    val environment: BenchmarkEnvironment,
    val results: List<BenchmarkResult>,
)

@Serializable
data class BenchmarkEnvironment(
    val os: String,
    val javaVersion: String,
    val availableProcessors: Int,
    val maxMemoryMb: Long,
)

private const val WARMUP_RUNS = 3
private const val TIMED_RUNS = 7

private fun generateOpenApiSpec(
    operations: Int,
    mutated: Boolean,
): String {
    val sb = StringBuilder()
    sb.append("openapi: 3.0.3\ninfo:\n  title: Benchmark API\n  version: 1.0.0\npaths:\n")
    repeat(operations) { index ->
        val id = if (mutated && index % 13 == 0) index + 10000 else index
        val extraRequired = if (mutated && index % 7 == 0) ", generatedAt" else ""
        val extraProperty = if (mutated && index % 7 == 0) "                  generatedAt:\n                    type: string\n" else ""
        // NOTE: every path must have a DISTINCT canonical identity
        // (/group<N>/{}); sibling templates that share an identity are
        // the same operation by ADR-001, which would collapse the
        // corpus into one op (recorded in docs/benchmarks.md).
        sb.append("  /group$index/{res$id}:\n")
        sb.append("    get:\n")
        sb.append("      parameters:\n")
        sb.append("        - name: res$id\n")
        sb.append("          in: path\n")
        sb.append("          required: true\n")
        sb.append("          schema:\n")
        sb.append("            type: string\n")
        sb.append("        - name: limit\n")
        sb.append("          in: query\n")
        sb.append("          required: false\n")
        sb.append("          schema:\n")
        sb.append("            type: integer\n")
        sb.append("      responses:\n")
        sb.append("        '200':\n")
        sb.append("          description: ok\n")
        sb.append("          content:\n")
        sb.append("            application/json:\n")
        sb.append("              schema:\n")
        sb.append("                type: object\n")
        sb.append("                required: [id$extraRequired]\n")
        sb.append("                properties:\n")
        sb.append("                  id:\n")
        sb.append("                    type: string\n")
        sb.append("                  name:\n")
        sb.append("                    type: string\n")
        sb.append("                  nested:\n")
        sb.append("                    type: object\n")
        sb.append("                    properties:\n")
        sb.append("                      address:\n")
        sb.append("                        type: object\n")
        sb.append("                        properties:\n")
        sb.append("                          city:\n")
        sb.append("                            type: string\n")
        sb.append("                          zip:\n")
        sb.append("                            type: string\n")
        sb.append("                  tags:\n")
        sb.append("                    type: array\n")
        sb.append("                    items:\n")
        sb.append("                      type: string\n")
        sb.append(extraProperty)
        sb.append("    post:\n")
        sb.append("      parameters:\n")
        sb.append("        - name: res$id\n")
        sb.append("          in: path\n")
        sb.append("          required: true\n")
        sb.append("          schema:\n")
        sb.append("            type: string\n")
        sb.append("      requestBody:\n")
        sb.append("        required: true\n")
        sb.append("        content:\n")
        sb.append("          application/json:\n")
        sb.append("            schema:\n")
        sb.append("              type: object\n")
        sb.append("              required: [name]\n")
        sb.append("              properties:\n")
        sb.append("                name:\n")
        sb.append("                  type: string\n")
        sb.append("                role:\n")
        sb.append("                  type: string\n")
        sb.append("                  enum: [admin, editor, viewer]\n")
        sb.append("      responses:\n")
        sb.append("        '201':\n")
        sb.append("          description: ok\n")
    }
    return sb.toString()
}

private fun generateGraphQlSdl(types: Int): String {
    val sb = StringBuilder()
    sb.append("type Query {\n")
    repeat(types) { index ->
        sb.append("  resource$index(id: ID!): Resource$index\n")
    }
    sb.append("}\n")
    repeat(types) { index ->
        sb.append(
            """
            type Resource$index {
              id: ID!
              name: String
              tags: [String!]
            }

            """.trimIndent(),
        )
    }
    return sb.toString()
}

private fun generateJsonSchema(properties: Int): String {
    val propertiesJson =
        (0 until properties).joinToString(",") { index ->
            """"field$index": {"type": "string", "minLength": $index}"""
        }
    return """{"title": "BigEvent", "type": "object", "properties": {$propertiesJson}, "required": ["field0"]}"""
}

private fun generateRegistry(consumers: Int): ConsumerRegistry =
    ConsumerRegistry(
        version = 1,
        consumers =
            (0 until consumers).map { index ->
                Consumer(
                    id = "consumer-$index",
                    kind = if (index % 2 == 0) ConsumerKind.FRONTEND else ConsumerKind.SERVICE,
                    contract = "benchmark-api",
                    selectors = listOf(parseSelector("consumer-$index", "*")),
                )
            },
    )

private fun timedMedian(runs: List<Double>): Double = runs.sorted()[runs.size / 2]

private fun benchmark(
    name: String,
    inputSize: String,
    block: () -> Unit,
): BenchmarkResult {
    repeat(WARMUP_RUNS) { block() }
    val timed = (0 until TIMED_RUNS).map { measureNanoTime { block() } / 1_000_000.0 }
    return BenchmarkResult(
        scenario = name,
        inputSize = inputSize,
        warmupRuns = WARMUP_RUNS,
        timedRuns = TIMED_RUNS,
        medianMs = timedMedian(timed),
        minMs = timed.min(),
    )
}

fun main() {
    // The JavaExec working directory is the :benchmark project dir.
    val repoRoot = File("").absoluteFile.parentFile
    val parser = OpenApiParser()
    val graphQl = GraphQlParser()
    val jsonSchema = JsonSchemaParser()

    val spec5k = generateOpenApiSpec(operations = 120, mutated = false)
    val spec5kMutated = generateOpenApiSpec(operations = 120, mutated = true)
    val specFile = Files.createTempDirectory("contractlens-bench").resolve("spec-5k.yaml")
    val specMutatedFile = Files.createTempDirectory("contractlens-bench").resolve("spec-5k-mutated.yaml")
    Files.writeString(specFile, spec5k)
    Files.writeString(specMutatedFile, spec5kMutated)
    val lines = spec5k.count { it == '\n' }

    val sdl = generateGraphQlSdl(types = 200)
    val event = generateJsonSchema(properties = 1000)

    val surfaceA = parser.parse(specFile, "benchmark-api")
    val surfaceB = parser.parse(specMutatedFile, "benchmark-api")
    val changes = DiffEngine.diff(surfaceA, surfaceB)
    val registry1k = generateRegistry(1000)
    val surfaceSmall = parser.parse(specFile, "benchmark-small") // used for classification-free paths below

    val results =
        listOf(
            benchmark("openapi-parse-5k", "5k lines / $lines lines / 120 ops / 240 operations total") {
                parser.parse(specFile, "benchmark-api")
            },
            benchmark("openapi-diff-5k", "two 5k-line specs (~30 structural changes)") {
                DiffEngine.diff(surfaceA, surfaceB)
            },
            benchmark("classify-diff", "change set of the 5k pair (${changes.size} changes)") {
                Classifier.classify(changes, surfaceA, surfaceB)
            },
            benchmark("impact-1k-consumers", "1,000-consumer registry over the 5k change set") {
                ConsumerMapper.map(changes, registry1k, "benchmark-api")
            },
            benchmark("generated-projection-diff", "project + diff both 5k surfaces (ts)") {
                DiffEngine.diff(
                    GeneratedClientProjection.project(surfaceA, GeneratorStyle.TYPESCRIPT),
                    GeneratedClientProjection.project(surfaceB, GeneratorStyle.TYPESCRIPT),
                )
            },
            benchmark("graphql-parse", "SDL with 200 query fields and 200 types") {
                graphQl.parse(sdl, "benchmark-graphql")
            },
            benchmark("json-schema-parse", "event schema with 1,000 properties") {
                jsonSchema.parse(event, "benchmark-event")
            },
            benchmark("registry-parse-1k", "registry YAML with 1,000 consumers (parse + validate)") {
                dev.bloopdex.contractlens.registry.RegistryParser
                    .parse(registryYaml1k, "benchmark-registry.yaml")
            },
            benchmark("snapshot-build-verify", "build + verify a snapshot of the 5k surface") {
                val document =
                    dev.bloopdex.contractlens.snapshot.buildSnapshot(
                        contract = "benchmark-api",
                        sourcePath = null,
                        identity =
                            dev.bloopdex.contractlens.snapshot
                                .SnapshotIdentity("git-commit", "c".repeat(40)),
                        capturedAt = "2026-08-19T00:00:00Z",
                        surface = surfaceSmall,
                    )
                dev.bloopdex.contractlens.snapshot
                    .snapshotJsonBytes(document)
            },
        )

    val environment =
        BenchmarkEnvironment(
            os = System.getProperty("os.name") + " " + System.getProperty("os.version"),
            javaVersion = System.getProperty("java.version"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
        )
    val baseline = BenchmarkBaseline(environment = environment, results = results)

    println("=== ContractLens Phase 5 performance baseline ===")
    println(
        "environment: ${environment.os} | java ${environment.javaVersion} | ${environment.availableProcessors} cpus | ${environment.maxMemoryMb} MB max heap",
    )
    results.forEach { result ->
        println("%-28s %-45s median %8.2f ms  (min %8.2f ms)".format(result.scenario, result.inputSize, result.medianMs, result.minMs))
    }

    val baselineFile = File(repoRoot, "docs/benchmarks/baseline.json")
    baselineFile.parentFile.mkdirs()
    baselineFile.writeText(CanonicalJson.encodeToString(BenchmarkBaseline.serializer(), baseline))
    println("baseline written to ${baselineFile.absolutePath}")
}

private val registryYaml1k: String =
    buildString {
        append("version: 1\nconsumers:\n")
        repeat(1000) { index ->
            append("  - id: consumer-$index\n")
            append("    kind: ${if (index % 2 == 0) "frontend" else "service"}\n")
            append("    contract: benchmark-api\n")
            append("    operations: [\"*\"]\n")
        }
    }
