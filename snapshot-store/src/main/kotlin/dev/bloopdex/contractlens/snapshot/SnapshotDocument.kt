// Snapshot format v1 (Phase 0, ADR-003).
//
// A snapshot is the canonical contract surface plus identity and
// integrity metadata. The `contentHash` covers exactly the
// deterministic envelope (format version, contract name, source path,
// identity, surface) — `capturedAt` is intentionally variable metadata
// and is excluded so that identical content always hashes identically.

package dev.bloopdex.contractlens.snapshot

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.hash.sha256Hex
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.core.serialization.canonicalJsonBytes
import kotlinx.serialization.Serializable

const val SNAPSHOT_FORMAT_VERSION = 1

@Serializable
data class SnapshotIdentity(
    val kind: String,
    val sha: String,
)

@Serializable
data class SnapshotDocument(
    val formatVersion: Int = SNAPSHOT_FORMAT_VERSION,
    val contract: String,
    val sourcePath: String?,
    val identity: SnapshotIdentity,
    val capturedAt: String?,
    val contentHash: String,
    val surface: ContractSurface,
)

/** Everything the content hash covers — all of it deterministic. */
@Serializable
private data class HashEnvelope(
    val formatVersion: Int,
    val contract: String,
    val sourcePath: String?,
    val identity: SnapshotIdentity,
    val surface: ContractSurface,
)

fun contentHashOf(
    contract: String,
    sourcePath: String?,
    identity: SnapshotIdentity,
    surface: ContractSurface,
): String =
    sha256Hex(
        canonicalJsonBytes(
            HashEnvelope(
                formatVersion = SNAPSHOT_FORMAT_VERSION,
                contract = contract,
                sourcePath = sourcePath,
                identity = identity,
                surface = surface.canonical(),
            ),
        ),
    )

fun buildSnapshot(
    contract: String,
    sourcePath: String?,
    identity: SnapshotIdentity,
    capturedAt: String,
    surface: ContractSurface,
): SnapshotDocument =
    SnapshotDocument(
        formatVersion = SNAPSHOT_FORMAT_VERSION,
        contract = contract,
        sourcePath = sourcePath,
        identity = identity,
        capturedAt = capturedAt,
        contentHash = contentHashOf(contract, sourcePath, identity, surface),
        surface = surface,
    )

/** Serialize a snapshot to its canonical byte form. */
fun snapshotJsonBytes(document: SnapshotDocument): ByteArray = canonicalJsonBytes(document)

/** Parse snapshot bytes and verify integrity (format + content hash). */
fun parseAndVerifySnapshot(
    bytes: ByteArray,
    origin: String,
): SnapshotDocument {
    val document =
        try {
            CanonicalJson.decodeFromString(SnapshotDocument.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            throw ContractError.InvalidSnapshot("cannot parse snapshot document ($origin): ${e.message}", e)
        }
    if (document.formatVersion != SNAPSHOT_FORMAT_VERSION) {
        throw ContractError.InvalidSnapshot(
            "unsupported snapshot format version ${document.formatVersion} (supported: $SNAPSHOT_FORMAT_VERSION) in $origin",
        )
    }
    val expected = contentHashOf(document.contract, document.sourcePath, document.identity, document.surface)
    if (document.contentHash != expected) {
        throw ContractError.SnapshotIntegrity(origin)
    }
    return document
}
