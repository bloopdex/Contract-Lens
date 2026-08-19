// CLI workflow tests: snapshot -> verify -> list end to end against a
// temp store, integrity failure surfaces the typed error, JSON output
// parses, and default runs stay quiet on stderr.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.exists

private val SMALL_SPEC = """
    openapi: 3.0.3
    info:
      title: Tiny API
      version: 1.0.0
    paths:
      /ping:
        get:
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    required: [status]
                    properties:
                      status:
                        type: string
""".trimIndent()

class CliTest : FunSpec({

    val sha = "c".repeat(40)

    fun tempSpec(): Pair<java.nio.file.Path, java.nio.file.Path> {
        val dir = Files.createTempDirectory("contractlens-cli")
        val spec = dir.resolve("spec.yaml")
        Files.writeString(spec, SMALL_SPEC)
        return dir to spec
    }

    test("snapshot, verify, and list complete the full workflow") {
        val (dir, spec) = tempSpec()
        val storeDir = dir.resolve("store")

        val snap = SnapshotCommand().test(arrayOf("--store", storeDir.toString(), "--sha", sha, spec.toString()))
        snap.statusCode shouldBe 0
        snap.stdout shouldContain "snapshot:"
        snap.stderr shouldBe "" // quiet by default: nothing logged at WARN
        val snapshotFile = storeDir.resolve("spec@$sha.snapshot.json")
        snapshotFile.exists() shouldBe true

        val verify = VerifyCommand().test(arrayOf(snapshotFile.toString()))
        verify.statusCode shouldBe 0
        verify.stdout shouldContain "verified: spec @ $sha"

        val list = ListCommand().test(arrayOf("--store", storeDir.toString(), "--json"))
        list.statusCode shouldBe 0
        val entries = CanonicalJson.decodeFromString(IndexEntryListSerializer, list.stdout.trim())
        entries.size shouldBe 1
        entries.single().contract shouldBe "spec"
        entries.single().error shouldBe null
    }

    test("snapshot --json emits a parseable summary") {
        val (dir, spec) = tempSpec()
        val snap = SnapshotCommand().test(arrayOf("--store", dir.toString(), "--sha", sha, "--json", spec.toString()))
        snap.statusCode shouldBe 0
        val summary = CanonicalJson.decodeFromString(SnapshotSummary.serializer(), snap.stdout.trim())
        summary.contract shouldBe "spec"
        summary.sha shouldBe sha
        summary.operations shouldBe 1
    }

    test("a corrupted snapshot is refused loudly by verify") {
        val (dir, spec) = tempSpec()
        val storeDir = dir.resolve("store")
        SnapshotCommand().test(arrayOf("--store", storeDir.toString(), "--sha", sha, spec.toString()))
        val snapshotFile = storeDir.resolve("spec@$sha.snapshot.json")
        val bytes = Files.readAllBytes(snapshotFile)
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0xFF).toByte()
        Files.write(snapshotFile, bytes)

        val e = shouldThrow<ContractError.SnapshotIntegrity> { VerifyCommand().test(arrayOf(snapshotFile.toString())) }
        e.code shouldBe "SNAPSHOT_INTEGRITY"
    }

    test("a missing contract file is a typed operational error") {
        val e = shouldThrow<ContractError.FileNotFound> {
            SnapshotCommand().test(arrayOf("--sha", sha, "no-such-file.yaml"))
        }
        e.code shouldBe "FILE_NOT_FOUND"
    }
})
