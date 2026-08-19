// Phase 5 fuzz harness for every untrusted-input boundary (OpenAPI,
// GraphQL SDL, JSON Schema, registry, usage graph).
//
// Methodology: seeded, corpus-driven mutation fuzzing + uniform random
// bytes. The corpus is every committed fixture document plus small
// inline seeds, so mutations stay near realistic inputs. Invariants per
// input:
//   - the outcome is controlled: either a parsed surface or a typed
//     ContractError — any other exception or a JVM crash is a failure
//   - determinism: the same input twice produces the same outcome
//     (equal surface or equal error code)
//
// Iteration count comes from the fuzz.iterations system property
// (default keeps the normal test run fast; `gradlew :cli:fuzz` raises
// it — see cli/build.gradle.kts). Jazzer (ADR-005) is deferred to
// Phase 6 CI: coverage-guided fuzzing adds most value as a long-running
// CI job, and the local harness above pins the invariants Jazzer would
// assert. Recorded high-iteration run: documented on the Phase 5 page.

package dev.bloopdex.contractlens.cli

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.graphql.GraphQlParser
import dev.bloopdex.contractlens.jsonschema.JsonSchemaParser
import dev.bloopdex.contractlens.openapi.OpenApiParser
import dev.bloopdex.contractlens.registry.RegistryParser
import dev.bloopdex.contractlens.registry.UsageParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.random.Random

private const val FUZZ_ITERATIONS_PROPERTY = "fuzz.iterations"

private fun fuzzIterations(): Int = (System.getProperty(FUZZ_ITERATIONS_PROPERTY) ?: "400").toInt()

private val SEED_CORPUS =
    listOf(
        "openapi: 3.0.3\ninfo: {title: T, version: 1.0.0}\npaths: {}\n",
        "openapi: 3.0.3\ninfo: {title: T, version: 1.0.0}\npaths:\n  /ping:\n    get:\n      responses:\n        '200':\n          description: ok\n",
        "type Query {\n  user(id: ID!): User\n}\ntype User {\n  id: ID!\n  email: String\n}\n",
        "{\"title\": \"E\", \"type\": \"object\", \"properties\": {\"a\": {\"type\": \"string\"}}, \"required\": [\"a\"]}",
        "version: 1\nconsumers:\n  - id: c\n    kind: service\n    contract: thorn-api\n    operations: [\"*\"]\n",
        "version: 1\nconsumers:\n  - id: c\n    contract: thorn-api\n    operations:\n      - operation: \"*\"\n        responseFields: [email]\n",
    ).map { it.toByteArray() }

private fun loadCorpus(): List<ByteArray> {
    val seeds = SEED_CORPUS.toMutableList()
    val fixtureRoot = ParserFuzzTest::class.java.getResource("/fixtures") ?: return seeds
    val root = Path.of(fixtureRoot.toURI())
    Files.walk(root).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { it.name.endsWith(".yaml") || it.name.endsWith(".json") || it.name.endsWith(".graphql") }
            .forEach { path ->
                try {
                    seeds += Files.readAllBytes(path)
                } catch (_: Exception) {
                    // corpus loading is best effort
                }
            }
    }
    return seeds
}

private class ByteFuzzer(
    seed: Long,
    private val corpus: List<ByteArray>,
) {
    private val random = Random(seed)

    fun next(): ByteArray {
        val fromCorpus = corpus.isNotEmpty() && random.nextInt(100) < 60
        val base = if (fromCorpus) corpus[random.nextInt(corpus.size)] else randomBytes(random.nextInt(1, 257))
        return if (fromCorpus) mutate(base) else base
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size) { random.nextInt(0, 256).toByte() }

    private fun mutate(bytes: ByteArray): ByteArray {
        var current = bytes
        val edits = random.nextInt(1, 9)
        repeat(edits) {
            current =
                when (random.nextInt(6)) {
                    0 -> flipByte(current)
                    1 -> insertBytes(current)
                    2 -> deleteSpan(current)
                    3 -> truncate(current)
                    4 -> duplicateSpan(current)
                    else -> swapSpans(current)
                }
        }
        return current
    }

    private fun flipByte(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val out = bytes.copyOf()
        val index = random.nextInt(out.size)
        out[index] = (out[index].toInt() xor (1 shl random.nextInt(8))).toByte()
        return out
    }

    private fun insertBytes(bytes: ByteArray): ByteArray {
        val insertion = randomBytes(random.nextInt(1, 17))
        val at = if (bytes.isEmpty()) 0 else random.nextInt(0, bytes.size + 1)
        return bytes.copyOfRange(0, at) + insertion + bytes.copyOfRange(at, bytes.size)
    }

    private fun deleteSpan(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val from = random.nextInt(bytes.size)
        val to = random.nextInt(from, bytes.size + 1)
        return bytes.copyOfRange(0, from) + bytes.copyOfRange(to, bytes.size)
    }

    private fun truncate(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        return bytes.copyOf(random.nextInt(0, bytes.size + 1))
    }

    private fun duplicateSpan(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val from = random.nextInt(bytes.size)
        val to = random.nextInt(from, bytes.size + 1)
        return bytes.copyOfRange(0, to) + bytes.copyOfRange(from, to) + bytes.copyOfRange(to, bytes.size)
    }

    private fun swapSpans(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        val a = random.nextInt(bytes.size)
        val b = random.nextInt(bytes.size)
        val out = bytes.copyOf()
        val tmp = out[a]
        out[a] = out[b]
        out[b] = tmp
        return out
    }
}

class ParserFuzzTest :
    FunSpec({

        test("parser fuzz: every input outcome is controlled, typed, and deterministic") {
            val iterations = fuzzIterations()
            val seed = 0x5eed_5eedL
            val fuzzer = ByteFuzzer(seed, loadCorpus())
            val tempDir = Files.createTempDirectory("contractlens-fuzz")
            val openApi = OpenApiParser()
            val graphQl = GraphQlParser()
            val jsonSchema = JsonSchemaParser()

            fun outcomeOf(parse: () -> Any?): String =
                try {
                    // The RESULT (not just success) is compared: identical
                    // inputs must produce identical surfaces/graphs.
                    "ok:${parse()}"
                } catch (e: ContractError) {
                    "error:${e.code}"
                } catch (e: Throwable) {
                    "CRASH:${e.javaClass.name}:${e.message}"
                }

            repeat(iterations) { index ->
                if (index > 0 && index % 5000 == 0) {
                    println("fuzz progress: $index / $iterations")
                }
                val bytes = fuzzer.next()
                val text = String(bytes, Charsets.ISO_8859_1)

                val file = tempDir.resolve("input-$index.yaml")
                Files.write(file, bytes)

                fun assertControlledAndDeterministic(
                    label: String,
                    parse: () -> Any?,
                ) {
                    val first = outcomeOf(parse)
                    if (first.startsWith("CRASH")) {
                        Files.write(tempDir.resolve("fuzz-failure-$label-#$index.bin"), bytes)
                        throw AssertionError("$label crashed on fuzz input #$index (seed=$seed): $first (input saved to $tempDir)")
                    }
                    val second = outcomeOf(parse)
                    if (first != second) {
                        Files.write(tempDir.resolve("fuzz-failure-$label-#$index.bin"), bytes)
                        throw AssertionError(
                            "$label nondeterministic on fuzz input #$index (seed=$seed): '$first' vs '$second' (input saved to $tempDir)",
                        )
                    }
                }

                assertControlledAndDeterministic("openapi") { openApi.parse(file, "fuzz") }
                assertControlledAndDeterministic("graphql") { graphQl.parse(text, "fuzz") }
                assertControlledAndDeterministic("json-schema") { jsonSchema.parse(text, "fuzz") }
                assertControlledAndDeterministic("registry") { RegistryParser.parse(text, "fuzz-registry.yaml") }
                assertControlledAndDeterministic("usage") { UsageParser.parse(text, "fuzz-usage.yaml") }
            }
        }
    })
