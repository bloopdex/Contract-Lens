// Consumer impact model (Phase 3) + operation-key derivation.
//
// The mapper answers exactly one question: which DECLARED consumers are
// affected by these structural changes? An ImpactReport carries the
// complete change set (unmatched changes stay visible) plus the
// per-consumer mapping. No severity is decided here — "affected" means
// "this consumer declares consumption of the changed surface", never
// "this consumer will definitely break" (the classifier's job, later).
//
// affectedOperations() derives the changed operation's canonical
// identity from the engine's location grammar (Phase 2, fixture-pinned):
//   - OPERATION_ADDED/REMOVED: location IS "METHOD /path"
//   - OPERATION_PATH_CHANGED: location is "METHOD /old-path" and from/to
//     carry the raw old/new paths — BOTH operations are affected
//   - everything else: the first " → "-separated segment of location
// A location outside that grammar yields no operation (defensive): the
// change stays visible in the report but maps to no consumer — never a
// crash, never a guessed match.

package dev.bloopdex.contractlens.core.impact

import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.model.pathIdentity
import dev.bloopdex.contractlens.core.registry.Consumer
import kotlinx.serialization.Serializable

@Serializable
data class ImpactedOperation(
    /** Lowercase HTTP method. */
    val method: String,
    /** Path template exactly as written in the contract. */
    val path: String,
    /** Identity form of [path] (every {param} becomes {}). */
    val pathIdentity: String,
)

@Serializable
data class ImpactedChange(
    val operation: ImpactedOperation,
    val change: ContractChange,
    /** Why the change maps to this consumer (explainable mapping). */
    val reason: String,
)

@Serializable
data class ConsumerImpact(
    val consumer: Consumer,
    /** Sorted by changeOrder; deduplicated by (change, operation). */
    val changes: List<ImpactedChange>,
)

@Serializable
data class ImpactReport(
    val contract: String,
    /** The complete structural change set — unmatched changes stay visible. */
    val changes: List<ContractChange>,
    /** Affected consumers only, sorted by consumer id. */
    val impacts: List<ConsumerImpact>,
)

/** Parse "METHOD /path-template" into an operation key; null when malformed. */
internal fun parseOperationKey(s: String): ImpactedOperation? {
    val separator = s.indexOf(' ')
    if (separator <= 0) return null
    val method = s.substring(0, separator).lowercase()
    val path = s.substring(separator + 1).trim()
    if (path.isEmpty() || !path.startsWith("/")) return null
    return ImpactedOperation(method = method, path = path, pathIdentity = pathIdentity(path))
}

/** The operations a change affects, in deterministic order (see the header). */
fun affectedOperations(change: ContractChange): List<ImpactedOperation> {
    if (change.target == ChangeTarget.OPERATION && change.kind == ChangeKind.OPERATION_PATH_CHANGED) {
        val base = parseOperationKey(change.location) ?: return emptyList()
        val operations = mutableListOf<ImpactedOperation>()
        change.from?.summary?.let { operations += ImpactedOperation(base.method, it, pathIdentity(it)) }
        change.to?.summary?.let { operations += ImpactedOperation(base.method, it, pathIdentity(it)) }
        return operations.sortedWith(compareBy({ it.pathIdentity }, { it.path }))
    }
    val operation = parseOperationKey(change.location.substringBefore(" → ")) ?: return emptyList()
    return listOf(operation)
}
