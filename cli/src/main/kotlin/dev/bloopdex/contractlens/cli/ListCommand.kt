// `contractlens snapshot list` — the store index (a directory scan that
// rebuilds on every run; see SnapshotStore). Corrupt entries are listed
// with their error, never hidden.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.snapshot.SnapshotStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Path
import kotlin.io.path.toPath

val IndexEntryListSerializer = ListSerializer(IndexEntrySummary.serializer())

@Serializable
data class IndexEntrySummary(
    val contract: String,
    val sha: String,
    val path: String,
    val error: String? = null,
)

class ListCommand : BaseCommand(
    name = "list",
    help = "List snapshots in the store (rebuilt index)",
) {
    private val store by option("--store", help = "Snapshot store directory (default: .contractlens/snapshots)")
        .file(canBeFile = false)

    private val jsonOut by option("--json", help = "Machine-readable JSON summary on stdout").flag()

    override fun runCommand() {
        val storeDir = store?.toPath() ?: Path.of(".contractlens/snapshots")
        val entries = SnapshotStore(storeDir).list()

        if (entries.isEmpty()) {
            println("no snapshots in ${storeDir.toAbsolutePath()}")
            return
        }
        if (jsonOut) {
            println(
                CanonicalJson.encodeToString(
                    IndexEntryListSerializer,
                    entries.map {
                        IndexEntrySummary(
                            contract = it.contract,
                            sha = it.sha,
                            path = it.path.toString(),
                            error = it.error,
                        )
                    }
                )
            )
        } else {
            entries.forEach {
                if (it.error != null) {
                    println("CORRUPT ${it.contract}@${it.sha} - ${it.error}")
                } else {
                    println("OK      ${it.contract}@${it.sha} (${it.document?.surface?.operations?.size ?: 0} operations)")
                }
            }
        }
    }
}
