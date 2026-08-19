// Structural change model (Phase 0's canonical Change model, Phase 2).
//
// A ContractChange is a STRUCTURAL FACT: what changed, where (logical
// location + physical source location), from what, to what, and a
// deterministic human explanation. `verdict` (breaking / non-breaking /
// review) is deliberately NOT decided here — the classifier layer fills
// it in a later phase; the diff engine must not know about consumers or
// breakage.
//
// Kind names describe structural facts, never verdicts.

package dev.bloopdex.contractlens.core.diff

import kotlinx.serialization.Serializable

@Serializable
enum class ChangeTarget {
    OPERATION,
    PARAMETER,
    REQUEST_BODY,
    RESPONSE,
    SCHEMA,
}

@Serializable
enum class ChangeKind {
    // operation-level
    OPERATION_ADDED,
    OPERATION_REMOVED,
    OPERATION_PATH_CHANGED,

    // parameter-level
    PARAMETER_ADDED,
    PARAMETER_REMOVED,
    PARAMETER_LOCATION_CHANGED,
    PARAMETER_REQUIRED_CHANGED,
    PARAMETER_SCHEMA_CHANGED,

    // request body / response-level
    REQUEST_BODY_ADDED,
    REQUEST_BODY_REMOVED,
    REQUEST_BODY_REQUIRED_CHANGED,
    CONTENT_TYPE_ADDED,
    CONTENT_TYPE_REMOVED,
    RESPONSE_ADDED,
    RESPONSE_REMOVED,

    // schema-level
    PROPERTY_ADDED,
    PROPERTY_REMOVED,
    REQUIRED_PROPERTY_ADDED,
    REQUIRED_PROPERTY_REMOVED,
    TYPE_CHANGED,
    NULLABLE_CHANGED,
    ENUM_CHANGED,
    CONSTRAINT_CHANGED,
    REF_TARGET_CHANGED,
    DEFAULT_CHANGED,
    ITEMS_CHANGED,
}

/** A summarized old/new value; null means "nothing existed there". */
@Serializable
data class ChangeValue(
    val summary: String,
)

@Serializable
data class ContractChange(
    val kind: ChangeKind,
    val target: ChangeTarget,
    /** Logical contract location, e.g. "GET /users → response 200 → properties.email". */
    val location: String,
    /** Physical source location from the canonical model (document-pointer path). */
    val sourceLocation: String?,
    val from: ChangeValue?,
    val to: ChangeValue?,
    /**
     * Breaking / non-breaking / review. Phase 2 leaves this null on
     * every change — the classifier (later phase) fills it.
     */
    val verdict: String? = null,
    /** Deterministic human explanation; printable without further inference. */
    val explanation: String,
)

/** Inverse kind pairs used by the direction-mirror invariant (visible to tests). */
internal val inverseKinds: Map<ChangeKind, ChangeKind> =
    mapOf(
        ChangeKind.OPERATION_ADDED to ChangeKind.OPERATION_REMOVED,
        ChangeKind.OPERATION_REMOVED to ChangeKind.OPERATION_ADDED,
        ChangeKind.PARAMETER_ADDED to ChangeKind.PARAMETER_REMOVED,
        ChangeKind.PARAMETER_REMOVED to ChangeKind.PARAMETER_ADDED,
        ChangeKind.REQUEST_BODY_ADDED to ChangeKind.REQUEST_BODY_REMOVED,
        ChangeKind.REQUEST_BODY_REMOVED to ChangeKind.REQUEST_BODY_ADDED,
        ChangeKind.CONTENT_TYPE_ADDED to ChangeKind.CONTENT_TYPE_REMOVED,
        ChangeKind.CONTENT_TYPE_REMOVED to ChangeKind.CONTENT_TYPE_ADDED,
        ChangeKind.RESPONSE_ADDED to ChangeKind.RESPONSE_REMOVED,
        ChangeKind.RESPONSE_REMOVED to ChangeKind.RESPONSE_ADDED,
        ChangeKind.PROPERTY_ADDED to ChangeKind.PROPERTY_REMOVED,
        ChangeKind.PROPERTY_REMOVED to ChangeKind.PROPERTY_ADDED,
        ChangeKind.REQUIRED_PROPERTY_ADDED to ChangeKind.REQUIRED_PROPERTY_REMOVED,
        ChangeKind.REQUIRED_PROPERTY_REMOVED to ChangeKind.REQUIRED_PROPERTY_ADDED,
    )

/** The kind diff(x, y) reports for the same change seen from y vs x. */
fun inverseKind(kind: ChangeKind): ChangeKind = inverseKinds[kind] ?: kind

/**
 * Total deterministic ordering over changes: location, then kind, then
 * from, then to. Never depends on hash-map iteration order.
 */
val changeOrder: Comparator<ContractChange> =
    Comparator
        .comparing<ContractChange, String> { it.location }
        .thenComparing { it.kind.name }
        .thenComparing { it.from?.summary ?: "" }
        .thenComparing { it.to?.summary ?: "" }

/** Deterministic human explanation for a change. */
fun explainChange(
    kind: ChangeKind,
    location: String,
    from: ChangeValue?,
    to: ChangeValue?,
): String {
    val base =
        when (kind) {
            ChangeKind.OPERATION_ADDED -> "operation was added"
            ChangeKind.OPERATION_REMOVED -> "operation was removed"
            ChangeKind.OPERATION_PATH_CHANGED -> "operation path template changed"
            ChangeKind.PARAMETER_ADDED -> "parameter was added"
            ChangeKind.PARAMETER_REMOVED -> "parameter was removed"
            ChangeKind.PARAMETER_LOCATION_CHANGED -> "parameter location changed"
            ChangeKind.PARAMETER_REQUIRED_CHANGED -> "parameter requiredness changed"
            ChangeKind.PARAMETER_SCHEMA_CHANGED -> "parameter schema changed"
            ChangeKind.REQUEST_BODY_ADDED -> "request body was added"
            ChangeKind.REQUEST_BODY_REMOVED -> "request body was removed"
            ChangeKind.REQUEST_BODY_REQUIRED_CHANGED -> "request body requiredness changed"
            ChangeKind.CONTENT_TYPE_ADDED -> "content type was added"
            ChangeKind.CONTENT_TYPE_REMOVED -> "content type was removed"
            ChangeKind.RESPONSE_ADDED -> "response was added"
            ChangeKind.RESPONSE_REMOVED -> "response was removed"
            ChangeKind.PROPERTY_ADDED -> "property was added"
            ChangeKind.PROPERTY_REMOVED -> "property was removed"
            ChangeKind.REQUIRED_PROPERTY_ADDED -> "property became required"
            ChangeKind.REQUIRED_PROPERTY_REMOVED -> "property is no longer required"
            ChangeKind.TYPE_CHANGED -> "type changed"
            ChangeKind.NULLABLE_CHANGED -> "nullability changed"
            ChangeKind.ENUM_CHANGED -> "enum values changed"
            ChangeKind.CONSTRAINT_CHANGED -> "constraints changed"
            ChangeKind.REF_TARGET_CHANGED -> "reference target changed"
            ChangeKind.DEFAULT_CHANGED -> "default presence changed"
            ChangeKind.ITEMS_CHANGED -> "array items changed"
        }
    val delta =
        when {
            from != null && to != null -> " from ${from.summary} to ${to.summary}"
            from != null -> " (was ${from.summary})"
            to != null -> " (now ${to.summary})"
            else -> ""
        }
    return "$base$delta at $location"
}
