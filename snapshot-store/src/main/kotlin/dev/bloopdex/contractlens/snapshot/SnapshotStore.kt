// File-backed snapshot store (Phase 0, ADR-003).
//
// Layout: <storeDir>/<contract>@<sha>.snapshot.json. The index is a
// directory scan — it rebuilds on every startup, so "index rebuilds
// correctly after restart" is true by construction and there is no
// separate index file to corrupt. Corrupt snapshot files are reported
// in the index with an error status; loading one fails loudly (never
// silently trusted, ADR-0027-style).

package dev.bloopdex.contractlens.snapshot

import dev.bloopdex.contractlens.core.error.ContractError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class SnapshotIndexEntry(
    val contract: String,
    val sha: String,
    val path: Path,
    /** Null when the file is corrupt and could not be trusted or even parsed. */
    val document: SnapshotDocument?,
    val error: String?,
)

class SnapshotStore(private val directory: Path) {

    private fun ensureStore(): Path {
        if (directory.exists() && !directory.isDirectory()) {
            throw ContractError.StoreError("'$directory' exists and is not a directory")
        }
        try {
            directory.createDirectories()
        } catch (e: Exception) {
            throw ContractError.StoreError("cannot create or use '$directory'", e)
        }
        return directory
    }

    private fun fileName(contract: String, sha: String): String =
        "${sanitize(contract)}@$sha.snapshot.json"

    private fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")

    /** Persist a snapshot atomically (temp file + atomic move). */
    fun save(document: SnapshotDocument): Path {
        val store = ensureStore()
        val target = store.resolve(fileName(document.contract, document.identity.sha))
        val temp = store.resolve("${target.name}.tmp")
        try {
            Files.write(temp, snapshotJsonBytes(document), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            try { Files.deleteIfExists(temp) } catch (_: Exception) { /* best effort */ }
            throw ContractError.StoreError("cannot write snapshot '${target.name}'", e)
        }
        return target
    }

    /** Load a snapshot by (contract, sha) with full integrity verification. */
    fun load(contract: String, sha: String): SnapshotDocument {
        val path = ensureStore().resolve(fileName(contract, sha))
        if (!path.exists()) {
            throw ContractError.StoreError("no snapshot for '$contract' at $sha (looked at $path)")
        }
        return loadFile(path)
    }

    /** Load and verify any snapshot file by path. */
    fun loadFile(path: Path): SnapshotDocument {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: Exception) {
            throw ContractError.StoreError("cannot read snapshot '$path'", e)
        }
        return parseAndVerifySnapshot(bytes, path.toString())
    }

    /** Rebuild the index by scanning the store directory. */
    fun list(): List<SnapshotIndexEntry> = try {
        val store = ensureStore()
        Files.list(store).use { stream ->
            stream.filter { it.name.endsWith(".snapshot.json") && !it.name.endsWith(".tmp") }
                .sorted()
                .map { path -> indexEntry(path) }
                .toList()
        }
    } catch (e: ContractError) {
        throw e
    } catch (e: Exception) {
        throw ContractError.StoreError("cannot scan the snapshot store", e)
    }

    private fun indexEntry(path: Path): SnapshotIndexEntry {
        val entry = try {
            val document = loadFile(path)
            SnapshotIndexEntry(document.contract, document.identity.sha, path, document, null)
        } catch (e: ContractError) {
            val names = path.name.removeSuffix(".snapshot.json").split('@')
            SnapshotIndexEntry(
                contract = names.getOrNull(0) ?: "unknown",
                sha = names.getOrNull(1) ?: "unknown",
                path = path,
                document = null,
                error = "${e.code}: ${e.message}",
            )
        }
        return entry
    }
}
