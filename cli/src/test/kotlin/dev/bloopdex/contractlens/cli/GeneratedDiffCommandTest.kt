// `contractlens generated-diff` CLI tests: the snapshot ->
// projection -> diff workflow for TS and Kotlin styles, JSON output,
// classification integration, and the exit-code flag.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private fun generatedSpec(email: Boolean) =
    """
    openapi: 3.0.3
    info: {title: G, version: 1.0.0}
    paths:
      /users/{id}:
        get:
          parameters:
            - {name: id, in: path, required: true, schema: {type: string}}
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    properties:
                      id: {type: string}
                      ${if (email) "email: {type: string}" else "age: {type: integer}"}
    """.trimIndent()

class GeneratedDiffCommandTest :
    FunSpec({

        val shaOld = "a".repeat(40)
        val shaNew = "b".repeat(40)

        fun capture(
            dir: Path,
            spec: String,
            name: String,
            sha: String,
        ): Path {
            val specPath = dir.resolve("$name.yaml")
            specPath.writeText(spec)
            val storeDir = dir.resolve("store")
            val result = SnapshotCommand().test(arrayOf("--store", storeDir.toString(), "--sha", sha, specPath.toString()))
            result.statusCode shouldBe 0
            return storeDir.resolve("$name@$sha.snapshot.json")
        }

        test("generated-diff projects both snapshots and reports client-shaped changes for ts and kotlin") {
            val dir = Files.createTempDirectory("contractlens-generated")
            val oldSnapshot = capture(dir, generatedSpec(email = true), "api", shaOld)
            val newSnapshot = capture(dir, generatedSpec(email = false), "api", shaNew)

            val ts = GeneratedDiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--style", "ts"))
            ts.statusCode shouldBe 0
            ts.stdout shouldContain "generated client (ts)"
            ts.stdout shouldContain "PROPERTY_REMOVED GET client.getUsersById → response return → schema → properties.email"

            val kotlin = GeneratedDiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--style", "kotlin"))
            kotlin.statusCode shouldBe 0
            kotlin.stdout shouldContain "generated client (kotlin)"
            kotlin.stdout shouldContain "PROPERTY_REMOVED GET client.getUsersById → response return → schema → properties.email"
        }

        test("--json emits the versioned generated report and --classify attaches verdicts") {
            val dir = Files.createTempDirectory("contractlens-generated")
            val oldSnapshot = capture(dir, generatedSpec(email = true), "api", shaOld)
            val newSnapshot = capture(dir, generatedSpec(email = false), "api", shaNew)

            val plain = GeneratedDiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--json"))
            val report = CanonicalJson.decodeFromString(GeneratedDiffReport.serializer(), plain.stdout.trim())
            report.format shouldBe "contractlens-generated-diff"
            report.version shouldBe 1
            report.style shouldBe "ts"
            report.changes.first { it.kind.name == "PROPERTY_REMOVED" }.location shouldContain "client.getUsersById"
            report.classification shouldBe null

            val command = GeneratedDiffCommand()
            val classified = command.test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--style", "ts", "--classify", "--json"))
            command.breakingFound shouldBe true
            val classifiedReport = CanonicalJson.decodeFromString(GeneratedDiffReport.serializer(), classified.stdout.trim())
            classifiedReport.classification!!.breaking shouldBe 1
            classifiedReport.classified!!
                .first { it.change.kind.name == "PROPERTY_REMOVED" }
                .verdict.name shouldBe "BREAKING"
        }

        test("an invalid style is a usage error") {
            val dir = Files.createTempDirectory("contractlens-generated")
            val oldSnapshot = capture(dir, generatedSpec(email = true), "api", shaOld)
            val newSnapshot = capture(dir, generatedSpec(email = false), "api", shaNew)
            val result = GeneratedDiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--style", "swift"))
            result.statusCode shouldBe 1
        }
    })
