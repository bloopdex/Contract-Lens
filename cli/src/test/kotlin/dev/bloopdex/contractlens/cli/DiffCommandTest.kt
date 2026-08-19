// CLI workflow tests for `contractlens diff`: snapshot -> snapshot ->
// diff, JSON output, typed errors, and the exit-code contract.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

private val DIFF_OLD =
    """
    openapi: 3.0.3
    info: {title: T, version: 1.0.0}
    paths:
      /ping:
        get:
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema: {type: string}
    """.trimIndent()

private val DIFF_NEW = DIFF_OLD.replace("{type: string}", "{type: integer}")

class DiffCommandTest :
    FunSpec({

        val shaOld = "a".repeat(40)
        val shaNew = "b".repeat(40)

        fun capture(
            dir: java.nio.file.Path,
            spec: String,
            name: String,
            sha: String,
        ): java.nio.file.Path {
            val specPath = dir.resolve("$name.yaml")
            Files.writeString(specPath, spec)
            val storeDir = dir.resolve("store")
            val result = SnapshotCommand().test(arrayOf("--store", storeDir.toString(), "--sha", sha, specPath.toString()))
            result.statusCode shouldBe 0
            return storeDir.resolve("$name@$sha.snapshot.json")
        }

        test("diff reports the structural change in both formats") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val newSnapshot = capture(dir, DIFF_NEW, "ping", shaNew)

            val human = DiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString()))
            human.statusCode shouldBe 0
            human.stdout shouldContain "changes: 1 (added 0, removed 0, changed 1)"
            human.stdout shouldContain "TYPE_CHANGED GET /ping → response 200 → schema"
            human.stdout shouldContain "string → integer"

            val json = DiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--json"))
            json.statusCode shouldBe 0
            val report = CanonicalJson.decodeFromString(DiffReport.serializer(), json.stdout.trim())
            report.format shouldBe "contractlens-diff"
            report.version shouldBe 1
            report.summary.total shouldBe 1
            report.changes.single().location shouldBe "GET /ping → response 200 → schema"
        }

        test("diffing identical snapshots reports no changes and exits 0") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val result = DiffCommand().test(arrayOf(oldSnapshot.toString(), oldSnapshot.toString()))
            result.statusCode shouldBe 0
            result.stdout shouldContain "changes: 0"
            result.stdout shouldContain "no structural changes"
        }

        test("a corrupted snapshot is refused loudly, never silently diffed") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val newSnapshot = capture(dir, DIFF_NEW, "ping", shaNew)
            val bytes = Files.readAllBytes(newSnapshot)
            bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0x01).toByte()
            Files.write(newSnapshot, bytes)

            shouldThrow<ContractError> { DiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString())) }
        }

        test("a missing snapshot argument is a clear usage error") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val result = DiffCommand().test(arrayOf(oldSnapshot.toString(), "no-such-snapshot.json"))
            result.statusCode shouldBe 1
            result.stderr shouldContain "no-such-snapshot.json"
        }

        test("diff --classify attaches verdicts, reasons, and semver labels, and sets the breaking flag") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val newSnapshot = capture(dir, DIFF_NEW, "ping", shaNew)

            val command = DiffCommand()
            val human = command.test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--classify"))
            human.statusCode shouldBe 0
            command.breakingFound shouldBe true
            human.stdout shouldContain "classification: 1 breaking, 0 non-breaking, 0 review"
            human.stdout shouldContain "semver: major"
            human.stdout shouldContain "[breaking] (major)"
            human.stdout shouldContain "reason: the accepted or emitted type changed"

            val jsonCommand = DiffCommand()
            val json = jsonCommand.test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--classify", "--json"))
            json.statusCode shouldBe 0
            jsonCommand.breakingFound shouldBe true
            val report = CanonicalJson.decodeFromString(DiffClassifiedReport.serializer(), json.stdout.trim())
            report.format shouldBe "contractlens-diff"
            report.version shouldBe 2
            report.classification.breaking shouldBe 1
            report.classification.semver shouldBe dev.bloopdex.contractlens.core.classify.SemverLevel.MAJOR
            report.classified.single().verdict shouldBe dev.bloopdex.contractlens.core.classify.Verdict.BREAKING
            report.classified
                .single()
                .change.verdict shouldBe "breaking"
        }

        test("diff --classify on identical snapshots leaves the breaking flag unset") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val command = DiffCommand()
            val result = command.test(arrayOf(oldSnapshot.toString(), oldSnapshot.toString(), "--classify"))
            result.statusCode shouldBe 0
            command.breakingFound shouldBe false
            result.stdout shouldContain "classification: 0 breaking, 0 non-breaking, 0 review"
        }

        test("plain diff output stays v1 with null verdicts (classifier never touches it)") {
            val dir = Files.createTempDirectory("contractlens-diff")
            val oldSnapshot = capture(dir, DIFF_OLD, "ping", shaOld)
            val newSnapshot = capture(dir, DIFF_NEW, "ping", shaNew)
            val json = DiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--json"))
            val report = CanonicalJson.decodeFromString(DiffReport.serializer(), json.stdout.trim())
            report.version shouldBe 1
            report.changes.single().verdict shouldBe null
        }
    })
