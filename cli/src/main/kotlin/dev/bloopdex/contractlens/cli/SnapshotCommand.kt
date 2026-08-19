// `contractlens snapshot <contract>` — Phase 1's capture workflow:
// parse an OpenAPI 3.0/3.1 document into the canonical model and
// persist a hash-verified snapshot keyed by git commit SHA.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.openapi.OpenApiParser
import dev.bloopdex.contractlens.snapshot.GitIdentity
import dev.bloopdex.contractlens.snapshot.SnapshotStore
import dev.bloopdex.contractlens.snapshot.buildSnapshot
import kotlinx.serialization.Serializable
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.toPath

@Serializable
data class SnapshotSummary(
    val contract: String,
    val sha: String,
    val path: String,
    val operations: Int,
    val contentHash: String,
)

class SnapshotCommand : BaseCommand(name = "snapshot") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = "Capture an OpenAPI 3.0/3.1 contract into a snapshot"

    private val contract by argument(
        name = "contract",
        help = "Path to an OpenAPI 3.0/3.1 document (YAML or JSON)",
    ).file(mustExist = true, canBeFile = true, mustBeReadable = true)

    private val name by option("--name", help = "Contract name (default: the file stem)")

    private val sha by option("--sha", help = "Commit identity (default: git rev-parse HEAD in the current directory)")

    private val store by option("--store", help = "Snapshot store directory (default: .contractlens/snapshots)")
        .file(canBeFile = false)

    private val jsonOut by option("--json", help = "Machine-readable JSON summary on stdout").flag()

    private val log = LoggerFactory.getLogger(SnapshotCommand::class.java)

    override fun runCommand() {
        val source = contract.toPath().toAbsolutePath().normalize()
        val contractName = name ?: source.fileName.toString().substringBeforeLast('.')
        val identitySha = GitIdentity.require(sha, Path.of("").toAbsolutePath())
        val storeDir = store?.toPath() ?: Path.of(".contractlens/snapshots")

        val surface = OpenApiParser().parse(source, contractName)
        val document =
            buildSnapshot(
                contract = contractName,
                sourcePath = source.toString(),
                identity =
                    dev.bloopdex.contractlens.snapshot
                        .SnapshotIdentity(kind = "git-commit", sha = identitySha),
                capturedAt = Instant.now().toString(),
                surface = surface,
            )
        val written = SnapshotStore(storeDir).save(document)

        log.debug(
            "snapshot written",
            StructuredArguments.kv("contract", contractName),
            StructuredArguments.kv("sha", identitySha),
            StructuredArguments.kv("path", written.toString()),
            StructuredArguments.kv("operations", surface.operations.size),
        )

        if (jsonOut) {
            echo(
                CanonicalJson.encodeToString(
                    SnapshotSummary.serializer(),
                    SnapshotSummary(
                        contract = contractName,
                        sha = identitySha,
                        path = written.toString(),
                        operations = surface.operations.size,
                        contentHash = document.contentHash,
                    ),
                ),
            )
        } else {
            echo("snapshot: ${written.toAbsolutePath()}")
            echo("contract: $contractName @ $identitySha (${surface.operations.size} operations)")
        }
    }
}
