// Usage-graph groundwork (Phase 4). The usage graph RECORDS which fields
// a consumer actually reads, per operation and direction — the data
// substrate for future usage-aware classification (Phase 4 follow-up:
// "warn only when actually-read fields change"). It is deliberately NOT
// wired into classification or mapping yet: the Phase 4 scope is the
// format, the typed model, and strict validation.
//
// Operation selection reuses the registry's canonical OperationSelector
// (ADR-002 identity: lowercase method + normalized path template), so a
// usage record and a registry selector always mean the same operation.
// Field paths are dotted property chains ("email",
// "profile.address.city") relative to the request body or response
// schema. Duplicate operations for one consumer merge deterministically
// (field lists union); duplicate (consumer, contract) records fail —
// identity is ambiguous.

package dev.bloopdex.contractlens.core.usage

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.registry.OperationSelector
import dev.bloopdex.contractlens.core.registry.parseSelector
import kotlinx.serialization.Serializable

const val USAGE_FORMAT_VERSION = 1

// --- raw document shape (what the YAML file literally contains) -------

@Serializable
data class RawUsageGraph(
    val version: Int? = null,
    val consumers: List<RawUsageRecord>? = null,
)

@Serializable
data class RawUsageRecord(
    val id: String? = null,
    val contract: String? = null,
    val operations: List<RawOperationUsage>? = null,
)

@Serializable
data class RawOperationUsage(
    val operation: String? = null,
    val requestFields: List<String> = emptyList(),
    val responseFields: List<String> = emptyList(),
)

// --- validated domain ------------------------------------------------

@Serializable
data class UsageGraph(
    val version: Int,
    /** Sorted by (consumer id, contract). */
    val records: List<UsageRecord>,
)

@Serializable
data class UsageRecord(
    /** The consumer's registry id — the stable identity (ADR-002). */
    val consumer: String,
    val contract: String,
    /** Deduplicated by selector identity (fields merged), sorted. */
    val operations: List<OperationUsage>,
)

@Serializable
data class OperationUsage(
    /** The canonical operation selector — "*" or METHOD + path-template. */
    val selector: OperationSelector,
    /** Dotted property paths the consumer reads from the request; sorted, deduplicated. */
    val requestFields: List<String>,
    /** Dotted property paths the consumer reads from the response; sorted, deduplicated. */
    val responseFields: List<String>,
)

// --- validation -------------------------------------------------------

fun validateFieldPath(
    consumerId: String,
    path: String,
): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) {
        throw ContractError.UsageInvalid("consumer '$consumerId': field paths must not be blank")
    }
    if (trimmed.startsWith(".") || trimmed.endsWith(".")) {
        throw ContractError.UsageInvalid("consumer '$consumerId': invalid field path '$path' (no leading or trailing dots)")
    }
    if (trimmed.split('.').any { it.isEmpty() }) {
        throw ContractError.UsageInvalid("consumer '$consumerId': invalid field path '$path' (empty segment)")
    }
    return trimmed
}

fun validateUsageGraph(
    raw: RawUsageGraph,
    source: String,
): UsageGraph {
    val version =
        raw.version ?: throw ContractError.UsageInvalid("$source: missing required field: version")
    if (version != USAGE_FORMAT_VERSION) {
        throw ContractError.UsageVersionUnsupported(version.toString())
    }
    val rawRecords =
        raw.consumers ?: throw ContractError.UsageInvalid("$source: missing required field: consumers")
    val records = mutableListOf<UsageRecord>()
    val seen = mutableSetOf<Pair<String, String>>()
    for ((index, entry) in rawRecords.withIndex()) {
        val consumer =
            entry.id?.takeUnless { it.isBlank() }
                ?: throw ContractError.UsageInvalid("$source: consumers[$index]: id is required and must not be blank")
        val contract =
            entry.contract?.takeUnless { it.isBlank() }
                ?: throw ContractError.UsageInvalid(
                    "$source: consumers[$index] (consumer '$consumer'): contract is required and must not be blank",
                )
        if (!seen.add(consumer to contract)) {
            throw ContractError.UsageDuplicateRecord(consumer, contract)
        }
        val operations =
            entry.operations
                ?: throw ContractError.UsageInvalid("$source: consumers[$index] (consumer '$consumer'): operations is required")
        val merged = LinkedHashMap<String, OperationUsage>()
        for (operation in operations) {
            val rawSelector =
                operation.operation
                    ?: throw ContractError.UsageInvalid("$source: consumers[$index] (consumer '$consumer'): operation selector is required")
            val selector = parseSelector(consumer, rawSelector)
            val key = if (selector.matchesAll) "*" else "${selector.method} ${selector.pathIdentity}"
            val existing = merged[key]
            val requestFields = (existing?.requestFields.orEmpty() + operation.requestFields).map { validateFieldPath(consumer, it) }
            val responseFields = (existing?.responseFields.orEmpty() + operation.responseFields).map { validateFieldPath(consumer, it) }
            merged[key] =
                OperationUsage(
                    selector = existing?.selector ?: selector,
                    requestFields = requestFields.sorted().distinct(),
                    responseFields = responseFields.sorted().distinct(),
                )
        }
        records +=
            UsageRecord(
                consumer = consumer,
                contract = contract,
                operations =
                    merged.values.sortedWith(
                        compareBy<OperationUsage> {
                            if (it.selector.matchesAll) "" else "${it.selector.method} ${it.selector.pathIdentity}"
                        },
                    ),
            )
    }
    return UsageGraph(
        version = version,
        records = records.sortedWith(compareBy({ it.consumer }, { it.contract })),
    )
}
