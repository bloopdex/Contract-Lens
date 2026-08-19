// `contractlens snapshot verify <file>` — integrity verification of a
// stored snapshot: format version and content hash. A modified or
// corrupted snapshot is refused loudly, never trusted.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.snapshot.parseAndVerifySnapshot
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.path.toPath

@Serializable
data class VerifySummary(
    val contract: String,
    val sha: String,
    val path: String,
    val verified: Boolean = true,
)

class VerifyCommand : BaseCommand(name = "verify") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Verify a snapshot's integrity (format version + content hash)"

    private val snapshot by argument(
        name = "snapshot",
        help = "Path to a .snapshot.json file",
    ).file(mustExist = true, canBeFile = true, mustBeReadable = true)

    private val jsonOut by option("--json", help = "Machine-readable JSON summary on stdout").flag()

    override fun runCommand() {
        val path = snapshot.toPath().toAbsolutePath().normalize()
        val document = parseAndVerifySnapshot(Files.readAllBytes(path), path.toString())

        if (jsonOut) {
            echo(
                CanonicalJson.encodeToString(
                    VerifySummary.serializer(),
                    VerifySummary(contract = document.contract, sha = document.identity.sha, path = path.toString()),
                ),
            )
        } else {
            echo("verified: ${document.contract} @ ${document.identity.sha}")
            echo("hash:     ${document.contentHash}")
            echo("captured: ${document.capturedAt ?: "unknown"}")
        }
    }
}
