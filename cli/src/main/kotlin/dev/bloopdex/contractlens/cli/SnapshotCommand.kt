// `contractlens snapshot <contract>` — Phase 1's capture workflow:
// parse an OpenAPI 3.0/3.1 document into the canonical model and
// persist a hash-verified snapshot keyed by git commit SHA.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.graphql.GraphQlParser
import dev.bloopdex.contractlens.jsonschema.JsonSchemaParser
import dev.bloopdex.contractlens.openapi.OpenApiParser
import dev.bloopdex.contractlens.snapshot.GitIdentity
import dev.bloopdex.contractlens.snapshot.SnapshotStore
import dev.bloopdex.contractlens.snapshot.buildSnapshot
import kotlinx.serialization.Serializable
import net.logstash.logback.argument.StructuredArguments
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.toPath

/** Format dispatch: --format wins; otherwise the file extension decides (GraphQL SDL only — everything else stays OpenAPI). */
internal fun parseContractSource(
    source: Path,
    contractName: String,
    format: String?,
): ContractSurface {
    if (format != null) {
        return when (format) {
            "openapi" -> OpenApiParser().parse(source, contractName)
            "graphql" -> GraphQlParser().parse(Files.readString(source), contractName)
            "json-schema" -> JsonSchemaParser().parse(Files.readString(source), contractName)
            else -> throw ContractError.InvalidStructure("unknown --format '$format' (supported: openapi, graphql, json-schema)")
        }
    }
    return when (
        source.fileName
            .toString()
            .substringAfterLast('.', "")
            .lowercase()
    ) {
        "graphql", "graphqls" -> GraphQlParser().parse(Files.readString(source), contractName)
        else -> OpenApiParser().parse(source, contractName)
    }
}

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

    private val format by
        option("--format", help = "Contract format (default: detected from the file extension; openapi | graphql | json-schema)")

    private val jsonOut by option("--json", help = "Machine-readable JSON summary on stdout").flag()

    override fun runCommand() {
        val source = contract.toPath().toAbsolutePath().normalize()
        val contractName = name ?: source.fileName.toString().substringBeforeLast('.')
        val identitySha = GitIdentity.require(sha, Path.of("").toAbsolutePath())
        val storeDir = store?.toPath() ?: Path.of(".contractlens/snapshots")

        val surface = parseContractSource(source, contractName, format)
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
