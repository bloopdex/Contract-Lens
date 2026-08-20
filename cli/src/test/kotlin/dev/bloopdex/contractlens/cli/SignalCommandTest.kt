// CLI tests for the signal emitter (ADR-008): payload shape on
// stdout, --output files, the privacy boundary end-to-end, failure
// behavior on unwritable outputs, and exit codes.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.error.ContractError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.writeText

private const val SIGNAL_SECRET_MARKER = "SIGNAL_SECRET_7c2d9e1f"

private val SIGNAL_SPEC =
    """
    openapi: 3.0.3
    info:
      title: Signal API
      version: 1.0.0
      description: contains $SIGNAL_SECRET_MARKER
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
                    description: $SIGNAL_SECRET_MARKER
                    properties:
                      status:
                        type: string
                        description: $SIGNAL_SECRET_MARKER
    """.trimIndent()

class SignalCommandTest :
    FunSpec({

        val sha = "a".repeat(40)

        fun snapshotStore(dir: java.nio.file.Path): java.nio.file.Path = dir.resolve("store")

        fun capture(
            name: String,
            path: java.nio.file.Path,
            shaValue: String,
        ) {
            val result =
                SnapshotCommand().test(
                    arrayOf("--store", snapshotStore(path.parent).toString(), "--name", name, "--sha", shaValue, path.toString()),
                )
            require(result.statusCode == 0) { "snapshot failed: ${result.output}" }
        }

        test("signal emits the v1 payload on stdout and sets the breaking flag") {
            val dir = Files.createTempDirectory("contractlens-signal")
            val spec = dir.resolve("signal.yaml")
            spec.writeText(SIGNAL_SPEC)
            val modified = dir.resolve("signal2.yaml")
            modified.writeText(SIGNAL_SPEC.replace("type: string", "type: integer"))
            capture("signal", spec, sha)
            capture("signal", modified, "b".repeat(40))

            val oldSnapshot = snapshotStore(dir).resolve("signal@$sha.snapshot.json")
            val newSnapshot = snapshotStore(dir).resolve("signal@${"b".repeat(40)}.snapshot.json")
            val command = SignalCommand()
            val result = command.test(arrayOf(oldSnapshot.toString(), newSnapshot.toString()))

            result.statusCode shouldBe 0 // the exit-1 mapping lives in main(), per the harness convention
            command.breakingFound shouldBe true // the pair has a breaking change
            result.stdout shouldContain "\"format\":\"contractlens-signal\""
            result.stdout shouldContain "\"version\":1"
            result.stdout shouldContain "\"contract\":\"signal\""
            result.stdout shouldContain "\"consumers\":null"
            result.stdout shouldNotContain SIGNAL_SECRET_MARKER
        }

        test("signal with a registry includes the consumers section") {
            val dir = Files.createTempDirectory("contractlens-signal")
            val spec = dir.resolve("signal.yaml")
            spec.writeText(SIGNAL_SPEC)
            val modified = dir.resolve("signal2.yaml")
            modified.writeText(SIGNAL_SPEC.replace("type: string", "type: integer"))
            capture("signal", spec, sha)
            capture("signal", modified, "b".repeat(40))
            val registryFile = dir.resolve("registry.yaml")
            registryFile.writeText(
                """
                version: 1
                consumers:
                  - id: ping-watcher
                    kind: service
                    contract: signal
                    operations: ["*"]
                """.trimIndent(),
            )

            val oldSnapshot = snapshotStore(dir).resolve("signal@$sha.snapshot.json")
            val newSnapshot = snapshotStore(dir).resolve("signal@${"b".repeat(40)}.snapshot.json")
            val result =
                SignalCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", registryFile.toString()),
                )

            result.statusCode shouldBe 0
            result.stdout shouldContain "\"consumers\":["
            result.stdout shouldContain "\"id\":\"ping-watcher\""
            result.stdout shouldContain "\"kind\":\"service\""
            result.stdout shouldNotContain SIGNAL_SECRET_MARKER
        }

        test("signal --output writes the payload to the file instead of stdout") {
            val dir = Files.createTempDirectory("contractlens-signal")
            val spec = dir.resolve("signal.yaml")
            spec.writeText(SIGNAL_SPEC)
            val modified = dir.resolve("signal2.yaml")
            modified.writeText(SIGNAL_SPEC.replace("type: string", "type: integer"))
            capture("signal", spec, sha)
            capture("signal", modified, "b".repeat(40))
            val output = dir.resolve("feed.json")

            val oldSnapshot = snapshotStore(dir).resolve("signal@$sha.snapshot.json")
            val newSnapshot = snapshotStore(dir).resolve("signal@${"b".repeat(40)}.snapshot.json")
            val result =
                SignalCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--output", output.toString()),
                )

            result.statusCode shouldBe 0
            result.stdout shouldBe ""
            Files.readString(output) shouldContain "\"format\":\"contractlens-signal\""
        }

        test("an unwritable --output fails with the typed OUTPUT_UNWRITABLE error") {
            val dir = Files.createTempDirectory("contractlens-signal")
            val spec = dir.resolve("signal.yaml")
            spec.writeText(SIGNAL_SPEC)
            val modified = dir.resolve("signal2.yaml")
            modified.writeText(SIGNAL_SPEC.replace("type: string", "type: integer"))
            capture("signal", spec, sha)
            capture("signal", modified, "b".repeat(40))

            val oldSnapshot = snapshotStore(dir).resolve("signal@$sha.snapshot.json")
            val newSnapshot = snapshotStore(dir).resolve("signal@${"b".repeat(40)}.snapshot.json")
            // a directory is not a writable output file
            val e =
                shouldThrow<ContractError.OutputUnwritable> {
                    SignalCommand().test(
                        arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--output", dir.toString()),
                    )
                }
            e.code shouldBe "OUTPUT_UNWRITABLE"
        }
    })
