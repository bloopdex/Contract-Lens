// Consumer registry domain model (Phase 0, ADR-002; implemented Phase 3).
//
// The registry is the source of DECLARED consumer knowledge: explicit,
// versioned, local-first, deterministic. Unregistered consumers are
// invisible by design — that honesty boundary is stated in every report.
// The shape mirrors Backstage's consumesApi relation so a future catalog
// import is trivial (ADR-002 trade-off).
//
// Two shapes exist deliberately:
//   - RawRegistry / RawConsumer: the exact on-disk YAML document shape
//     (the :registry adapter decodes into it with kaml, ADR-005).
//   - ConsumerRegistry / Consumer: the validated typed domain, produced
//     by validateRegistry(). Version checked, identities checked,
//     selectors parsed into canonical form, consumers sorted by id.
//     Everything downstream of the parser consumes only this domain.
//
// Validation policy (deliberate, documented):
//   - unsupported version -> typed error (never reinterpreted)
//   - duplicate consumer ids -> typed error (ids are the identity)
//   - blank id/contract, unknown kind, empty operations -> typed error
//   - selectors must be "*" or "METHOD /path-template"; equivalent
//     selectors dedupe to the first occurrence
//   - unknown YAML fields fail (kaml strict mode in the adapter)

package dev.bloopdex.contractlens.core.registry

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.pathIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val REGISTRY_FORMAT_VERSION = 1

// --- raw document shape (what the YAML file literally contains) -------

@Serializable
data class RawRegistry(
    val version: Int? = null,
    val consumers: List<RawConsumer>? = null,
)

@Serializable
data class RawConsumer(
    val id: String? = null,
    val kind: String? = null,
    val contract: String? = null,
    val operations: List<String>? = null,
    val contact: String? = null,
    val notes: String? = null,
)

// --- validated domain ------------------------------------------------

@Serializable
data class ConsumerRegistry(
    val version: Int,
    /** Sorted by id; ids are unique (validated). */
    val consumers: List<Consumer>,
)

/** The consumer kinds ADR-002 names; the registry version gates future additions. */
@Serializable
enum class ConsumerKind {
    @SerialName("frontend")
    FRONTEND,

    @SerialName("service")
    SERVICE,

    @SerialName("sdk")
    SDK,

    @SerialName("generated-client")
    GENERATED_CLIENT,

    @SerialName("integration")
    INTEGRATION,
}

@Serializable
data class Consumer(
    /** Stable identity — never array position, display name, or YAML ordering. */
    val id: String,
    val kind: ConsumerKind,
    /** The contract name this consumer declares consumption of (matches snapshot contract). */
    val contract: String,
    /** Normalized, deduplicated (first occurrence wins), sorted selectors. */
    val selectors: List<OperationSelector>,
    val contact: String? = null,
    val notes: String? = null,
)

@Serializable
data class OperationSelector(
    /** The selector exactly as written in the registry (explainability). */
    val raw: String,
    /** "*" — selects every operation of the contract. */
    val matchesAll: Boolean,
    /** Lowercase HTTP method; null when [matchesAll]. */
    val method: String? = null,
    /** Path template as written; null when [matchesAll]. */
    val path: String? = null,
    /** Identity form of [path] (every {param} becomes {}); null when [matchesAll]. */
    val pathIdentity: String? = null,
) {
    /**
     * Selector/operation matching uses the canonical operation identity
     * (ADR-001): lowercase method + normalized path template. "/users/{id}"
     * and "/users/{userId}" are the same operation.
     */
    fun matches(
        method: String,
        pathIdentity: String,
    ): Boolean = matchesAll || (this.method == method && this.pathIdentity == pathIdentity)
}

// --- validation -------------------------------------------------------

/**
 * Parse one operation selector into canonical form: "*" (all operations)
 * or "METHOD /path-template" (ADR-002). Methods are stored lowercase to
 * match the canonical model; the template identity uses the same
 * normalization as operations.
 */
fun parseSelector(
    consumerId: String,
    raw: String,
): OperationSelector {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        throw ContractError.RegistrySelectorInvalid(consumerId, raw, "selector must not be blank")
    }
    if (trimmed == "*") {
        return OperationSelector(raw = raw, matchesAll = true)
    }
    val separator = trimmed.indexOf(' ')
    if (separator <= 0) {
        throw ContractError.RegistrySelectorInvalid(consumerId, raw, "expected 'METHOD /path' (or '*' to select all operations)")
    }
    val method = trimmed.substring(0, separator).lowercase()
    val path = trimmed.substring(separator + 1).trim()
    if (path.isEmpty() || !path.startsWith("/")) {
        throw ContractError.RegistrySelectorInvalid(consumerId, raw, "path template must start with '/'")
    }
    if (path.contains(' ')) {
        throw ContractError.RegistrySelectorInvalid(consumerId, raw, "path template must not contain spaces")
    }
    return OperationSelector(
        raw = raw,
        matchesAll = false,
        method = method,
        path = path,
        pathIdentity = pathIdentity(path),
    )
}

/**
 * Validate the raw registry document into the typed domain. Every
 * failure is a typed [ContractError] with a stable code; nothing is
 * silently reinterpreted.
 */
fun validateRegistry(
    raw: RawRegistry,
    source: String,
): ConsumerRegistry {
    val version =
        raw.version ?: throw ContractError.RegistryInvalid("$source: missing required field: version")
    if (version != REGISTRY_FORMAT_VERSION) {
        throw ContractError.RegistryVersionUnsupported(version.toString())
    }
    val rawConsumers =
        raw.consumers ?: throw ContractError.RegistryInvalid("$source: missing required field: consumers")
    val consumers = rawConsumers.mapIndexed { index, entry -> validateConsumer(entry, source, index) }
    val duplicate =
        consumers
            .map { it.id }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
            .firstOrNull()
    if (duplicate != null) {
        throw ContractError.RegistryDuplicateId(duplicate)
    }
    return ConsumerRegistry(version = version, consumers = consumers.sortedBy { it.id })
}

private fun validateConsumer(
    raw: RawConsumer,
    source: String,
    index: Int,
): Consumer {
    val id =
        raw.id?.takeUnless { it.isBlank() }
            ?: throw ContractError.RegistryInvalid("$source: consumers[$index]: id is required and must not be blank")
    val kindName =
        raw.kind?.takeUnless { it.isBlank() }
            ?: throw ContractError.RegistryInvalid("$source: consumers[$index] (consumer '$id'): kind is required")
    val kind =
        when (kindName) {
            "frontend" -> ConsumerKind.FRONTEND
            "service" -> ConsumerKind.SERVICE
            "sdk" -> ConsumerKind.SDK
            "generated-client" -> ConsumerKind.GENERATED_CLIENT
            "integration" -> ConsumerKind.INTEGRATION
            else ->
                throw ContractError.RegistryInvalid(
                    "$source: consumers[$index] (consumer '$id'): unknown consumer kind '$kindName' " +
                        "(allowed: frontend, service, sdk, generated-client, integration)",
                )
        }
    val contract =
        raw.contract?.takeUnless { it.isBlank() }
            ?: throw ContractError.RegistryInvalid(
                "$source: consumers[$index] (consumer '$id'): contract is required and must not be blank",
            )
    val operations =
        raw.operations
            ?: throw ContractError.RegistryInvalid(
                "$source: consumers[$index] (consumer '$id'): operations is required (use [\"*\"] to select all operations)",
            )
    if (operations.isEmpty()) {
        throw ContractError.RegistryInvalid(
            "$source: consumers[$index] (consumer '$id'): operations must not be empty (use [\"*\"] to select all operations)",
        )
    }
    val selectors = operations.map { parseSelector(id, it) }
    // Equivalent selectors (same canonical key, e.g. "*" twice or
    // "/users/{id}" vs "/users/{userId}") collapse to the first
    // occurrence — deterministic, never an error. Ordering is canonical.
    val deduplicated = LinkedHashMap<String, OperationSelector>()
    for (selector in selectors) {
        val key = if (selector.matchesAll) "*" else "${selector.method} ${selector.pathIdentity}"
        deduplicated.putIfAbsent(key, selector)
    }
    val sorted =
        deduplicated.values.sortedWith(
            compareBy<OperationSelector> { if (it.matchesAll) "" else "${it.method} ${it.pathIdentity}" },
        )
    return Consumer(
        id = id,
        kind = kind,
        contract = contract,
        selectors = sorted,
        contact = raw.contact,
        notes = raw.notes,
    )
}
