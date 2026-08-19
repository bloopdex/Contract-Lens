// The classifier layer (Phase 0 ADR-001 ruleset, implemented Phase 4).
//
// Pure functions over the change set and the two surfaces (used only
// for the two documented contextual lookups: requiredness of an added
// parameter, and default-presence softening of a required property
// addition). No IO, no CLI, no consumers, no mutation of its inputs.
//
// Ruleset summary (ADR-001, direction-aware — for REQUEST changes the
// consumer sends and the provider validates; for RESPONSE changes the
// provider sends and the consumer reads):
//   operation added -> non-breaking; removed -> breaking
//   path template variable rename (identity unchanged) -> non-breaking;
//   real path change -> breaking
//   parameter: removed -> breaking; required added -> breaking; optional
//   added -> non-breaking; required-flip -> breaking; optional-flip ->
//   non-breaking; location move -> review (no documented rule)
//   request property: removed -> review (unknown-field tolerance);
//   required added -> breaking (softened to review by a JSON Schema
//   default); optional added -> non-breaking; optional->required ->
//   breaking; required->optional -> non-breaking
//   response property: removed -> breaking; added -> non-breaking;
//   required added -> review; required->optional -> breaking;
//   optional->required -> non-breaking
//   type change -> breaking in both directions
//   enum: any value removed -> breaking; added-only: request ->
//   non-breaking, response -> review (x-extensible-enum unsupported)
//   constraint: tightened -> review; relaxed -> non-breaking
//   status: removed -> breaking; added -> review
//   content type: removed -> breaking; added -> non-breaking
//   nullability: request non-null->nullable -> non-breaking,
//   nullable->non-null -> review; response: reversed
//   rename candidates (same-type property add+remove pair in one
//   schema) -> review, never auto-classified
//   kinds without a documented rule (REF_TARGET_CHANGED,
//   DEFAULT_CHANGED, ITEMS_CHANGED, REQUEST_BODY_REQUIRED_CHANGED
//   derives from the direction analysis) -> the rule noted inline
//
// Conservative policy: whenever a direction-relevant rule cannot
// determine the direction, the verdict is review — never a silent
// breaking or non-breaking guess.

package dev.bloopdex.contractlens.core.classify

import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.additiveKinds
import dev.bloopdex.contractlens.core.diff.changeOrder
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.SchemaNode
import dev.bloopdex.contractlens.core.model.pathIdentity

internal enum class Direction {
    REQUEST,
    RESPONSE,
}

object Classifier {
    fun classify(
        changes: List<ContractChange>,
        oldSurface: ContractSurface,
        newSurface: ContractSurface,
    ): ClassificationReport {
        val ordered = changes.sortedWith(changeOrder)
        val renamePairs = renameCandidatePairs(ordered)
        val classified = ordered.map { change -> classifyOne(change, newSurface, renamePairs) }
        return ClassificationReport(changes = classified, summary = ClassificationSummaryBuilder.of(classified))
    }

    // --- classification entry -----------------------------------------

    private fun classifyOne(
        change: ContractChange,
        newSurface: ContractSurface,
        renamePairs: Set<String>,
    ): ClassifiedChange {
        val direction = directionOf(change)
        val outcome =
            if (change.location in renamePairs) {
                RENAME_CANDIDATE_REASON to Verdict.REVIEW
            } else {
                rule(change, direction, newSurface)
            }
        val (reason, verdict) = outcome
        return ClassifiedChange(
            change = change.copy(verdict = verdict.name.lowercase().replace('_', '-')),
            verdict = verdict,
            semver = semverOf(verdict, change.kind),
            reason = reason,
        )
    }

    private fun rule(
        change: ContractChange,
        direction: Direction?,
        newSurface: ContractSurface,
    ): Pair<String, Verdict> =
        when (change.kind) {
            ChangeKind.OPERATION_ADDED -> "new operations are invisible to existing consumers" to Verdict.NON_BREAKING
            ChangeKind.OPERATION_REMOVED -> "consumers of this operation lose it" to Verdict.BREAKING
            ChangeKind.OPERATION_PATH_CHANGED -> pathChangeRule(change)
            ChangeKind.PARAMETER_ADDED -> parameterAddedRule(change, newSurface)
            ChangeKind.PARAMETER_REMOVED ->
                "consumers still sending this parameter lose it (validation may reject unknown parameters)" to
                    Verdict.BREAKING
            ChangeKind.PARAMETER_LOCATION_CHANGED ->
                "the parameter moved between locations; the impact depends on how consumers send it" to Verdict.REVIEW
            ChangeKind.PARAMETER_REQUIRED_CHANGED ->
                if (change.to?.summary == "true") {
                    "consumers not sending this parameter now fail validation" to Verdict.BREAKING
                } else {
                    "sending the parameter remains valid; it is no longer required" to Verdict.NON_BREAKING
                }
            ChangeKind.PARAMETER_SCHEMA_CHANGED -> parameterSchemaRule(change)
            ChangeKind.REQUEST_BODY_ADDED ->
                if (change.to?.summary == "required: true") {
                    "consumers must now send a body they previously omitted" to Verdict.BREAKING
                } else {
                    "the new body is optional for existing consumers" to Verdict.NON_BREAKING
                }
            ChangeKind.REQUEST_BODY_REMOVED -> "consumers still sending the body may be rejected" to Verdict.BREAKING
            ChangeKind.REQUEST_BODY_REQUIRED_CHANGED ->
                if (change.to?.summary == "true") {
                    "consumers omitting the body now fail (direction analysis: request)" to Verdict.BREAKING
                } else {
                    "omitting the body is now accepted" to Verdict.NON_BREAKING
                }
            ChangeKind.CONTENT_TYPE_ADDED -> "an additional representation is available" to Verdict.NON_BREAKING
            ChangeKind.CONTENT_TYPE_REMOVED -> "consumers requesting this representation lose it" to Verdict.BREAKING
            ChangeKind.RESPONSE_ADDED -> "a new response status may appear; clients may not handle it" to Verdict.REVIEW
            ChangeKind.RESPONSE_REMOVED -> "clients handling this status can no longer receive it" to Verdict.BREAKING
            ChangeKind.PROPERTY_REMOVED -> propertyRemovedRule(direction)
            ChangeKind.PROPERTY_ADDED -> "new properties are invisible to existing consumers" to Verdict.NON_BREAKING
            ChangeKind.REQUIRED_PROPERTY_ADDED -> requiredPropertyAddedRule(direction, change, newSurface)
            ChangeKind.REQUIRED_PROPERTY_REMOVED -> requiredPropertyRemovedRule(direction)
            ChangeKind.TYPE_CHANGED -> "the accepted or emitted type changed" to Verdict.BREAKING
            ChangeKind.NULLABLE_CHANGED -> nullableRule(change, direction)
            ChangeKind.ENUM_CHANGED -> enumRule(change, direction)
            ChangeKind.CONSTRAINT_CHANGED -> constraintRule(change)
            ChangeKind.REF_TARGET_CHANGED -> "the referenced schema changed; the substitution's compatibility is unknown" to Verdict.REVIEW
            ChangeKind.DEFAULT_CHANGED -> "defaults changed; the effect on consumers is context-dependent" to Verdict.REVIEW
            ChangeKind.ITEMS_CHANGED -> "the array item schema changed; the effect is context-dependent" to Verdict.REVIEW
        }

    // --- per-kind rules ------------------------------------------------

    private fun pathChangeRule(change: ContractChange): Pair<String, Verdict> {
        val from = change.from?.summary
        val to = change.to?.summary
        val sameIdentity = from != null && to != null && pathIdentity(from) == pathIdentity(to)
        return if (sameIdentity) {
            "the path template variable was renamed; the canonical operation identity is unchanged" to Verdict.NON_BREAKING
        } else {
            "the operation was removed from its old path and added at a new one" to Verdict.BREAKING
        }
    }

    private fun parameterAddedRule(
        change: ContractChange,
        newSurface: ContractSurface,
    ): Pair<String, Verdict> {
        val identity = parameterIdentityOf(change) ?: return UNKNOWN_DIRECTION_REASON to Verdict.REVIEW
        val parameter = findParameter(newSurface, identity)
        return if (parameter?.required == true) {
            "consumers do not send this newly required parameter" to Verdict.BREAKING
        } else if (parameter != null) {
            "existing consumers are unaffected by an optional new parameter" to Verdict.NON_BREAKING
        } else {
            "the added parameter's requiredness could not be determined from the new surface" to Verdict.REVIEW
        }
    }

    private fun parameterSchemaRule(change: ContractChange): Pair<String, Verdict> {
        val from = change.from?.summary
        val to = change.to?.summary
        return if (from == "no schema" || to == "no schema") {
            "the parameter gained or lost a schema; the impact depends on provider validation" to Verdict.REVIEW
        } else {
            "the parameter's accepted type changed (type-change rule, request direction)" to Verdict.BREAKING
        }
    }

    private fun propertyRemovedRule(direction: Direction?): Pair<String, Verdict> =
        when (direction) {
            Direction.RESPONSE -> "the response no longer guarantees a property consumers may read" to Verdict.BREAKING
            Direction.REQUEST -> "removed request properties depend on the provider's unknown-field tolerance" to Verdict.REVIEW
            null -> UNKNOWN_DIRECTION_REASON to Verdict.REVIEW
        }

    private fun requiredPropertyAddedRule(
        direction: Direction?,
        change: ContractChange,
        newSurface: ContractSurface,
    ): Pair<String, Verdict> =
        when (direction) {
            Direction.REQUEST ->
                if (propertyHasDefault(change, newSurface)) {
                    "the newly required request property carries a JSON Schema default, softening the addition" to Verdict.REVIEW
                } else {
                    "consumers that omit the new required property fail validation" to Verdict.BREAKING
                }
            Direction.RESPONSE -> "strict clients may reject responses carrying an unknown required property" to Verdict.REVIEW
            null -> UNKNOWN_DIRECTION_REASON to Verdict.REVIEW
        }

    private fun requiredPropertyRemovedRule(direction: Direction?): Pair<String, Verdict> =
        when (direction) {
            Direction.RESPONSE -> "clients must now handle the property's absence" to Verdict.BREAKING
            Direction.REQUEST -> "the property is no longer required; sending it remains valid" to Verdict.NON_BREAKING
            null -> UNKNOWN_DIRECTION_REASON to Verdict.REVIEW
        }

    private fun nullableRule(
        change: ContractChange,
        direction: Direction?,
    ): Pair<String, Verdict> {
        val becameNullable = change.from?.summary == "false" && change.to?.summary == "true"
        val becameNonNull = change.from?.summary == "true" && change.to?.summary == "false"
        return when {
            direction == Direction.REQUEST && becameNullable ->
                "the request accepts null where it previously rejected it" to Verdict.NON_BREAKING
            direction == Direction.REQUEST && becameNonNull ->
                "the request rejects null where it previously accepted it" to Verdict.REVIEW
            direction == Direction.RESPONSE && becameNullable ->
                "the response may now emit null where it previously never did" to Verdict.REVIEW
            direction == Direction.RESPONSE && becameNonNull ->
                "the response no longer emits null for this field" to Verdict.NON_BREAKING
            else -> UNKNOWN_DIRECTION_REASON to Verdict.REVIEW
        }
    }

    private fun enumRule(
        change: ContractChange,
        direction: Direction?,
    ): Pair<String, Verdict> {
        val removed =
            change.from
                ?.summary
                ?.split(", ")
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val added =
            change.to
                ?.summary
                ?.split(", ")
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val removedValues = removed - added.toSet()
        return if (removedValues.isNotEmpty()) {
            "values were removed from the enum" to Verdict.BREAKING
        } else {
            when (direction) {
                Direction.REQUEST -> "new values extend what the request accepts" to Verdict.NON_BREAKING
                Direction.RESPONSE ->
                    "the response may emit new values clients do not recognize " +
                        "(non-breaking only under an x-extensible-enum annotation, which the model does not carry)" to Verdict.REVIEW
                null -> UNKNOWN_DIRECTION_REASON to Verdict.REVIEW
            }
        }
    }

    private fun constraintRule(change: ContractChange): Pair<String, Verdict> {
        val from = change.from?.summary.orEmpty()
        val to = change.to?.summary.orEmpty()
        val tightened = hasTightenedConstraint(from, to)
        val relaxed = hasTightenedConstraint(to, from)
        return when {
            tightened && !relaxed -> "the contract narrowed its constraints" to Verdict.REVIEW
            relaxed && !tightened -> "the contract relaxed its constraints" to Verdict.NON_BREAKING
            else -> "constraints both tightened and relaxed" to Verdict.REVIEW
        }
    }

    // --- semver --------------------------------------------------------

    private fun semverOf(
        verdict: Verdict,
        kind: ChangeKind,
    ): SemverLevel? =
        when (verdict) {
            Verdict.BREAKING -> SemverLevel.MAJOR
            Verdict.NON_BREAKING -> if (kind in additiveKinds) SemverLevel.MINOR else SemverLevel.PATCH
            Verdict.REVIEW -> null
        }

    // --- direction -----------------------------------------------------

    internal fun directionOf(change: ContractChange): Direction? =
        when (change.target) {
            ChangeTarget.PARAMETER, ChangeTarget.REQUEST_BODY -> Direction.REQUEST
            ChangeTarget.RESPONSE -> Direction.RESPONSE
            ChangeTarget.OPERATION, ChangeTarget.SCHEMA ->
                when {
                    change.location.contains(SEGMENT_REQUEST_BODY) -> Direction.REQUEST
                    change.location.contains(SEGMENT_PARAMETER) -> Direction.REQUEST
                    change.location.contains(SEGMENT_RESPONSE) -> Direction.RESPONSE
                    else -> null
                }
        }

    // --- rename candidates ---------------------------------------------

    /**
     * Same-type property add+remove pairs within ONE schema are rename
     * candidates (ADR-001): both members get review, never auto-breaking.
     * Pairing is deterministic: candidates sorted by location; first
     * unused removal pairs with the first unused addition of the same
     * type summary. Returns the locations of BOTH pair members.
     */
    internal fun renameCandidatePairs(changes: List<ContractChange>): Set<String> {
        val removals =
            changes.filter { it.kind == ChangeKind.PROPERTY_REMOVED && it.target == ChangeTarget.SCHEMA }
        val additions =
            changes.filter { it.kind == ChangeKind.PROPERTY_ADDED && it.target == ChangeTarget.SCHEMA }
        val paired = mutableSetOf<String>()
        val usedAdditions = mutableSetOf<String>()
        for (removal in removals.sortedBy { it.location }) {
            val parent = removal.location.substringBeforeLast(PROPERTIES_SEGMENT)
            val type = removal.from?.summary ?: continue
            val match =
                additions
                    .sortedBy { it.location }
                    .firstOrNull { addition ->
                        addition.location !in usedAdditions &&
                            addition.location.substringBeforeLast(PROPERTIES_SEGMENT) == parent &&
                            addition.to?.summary == type
                    } ?: continue
            paired += removal.location
            paired += match.location
            usedAdditions += match.location
        }
        return paired
    }

    // --- surface lookups (documented contextual rules) ------------------

    private fun parameterIdentityOf(change: ContractChange): ParameterIdentity? {
        val segments = change.location.split(ARROW_SEPARATOR)
        if (segments.size < 2) return null
        val operation = parseOperationKey(segments[0]) ?: return null
        // Engine grammar: 'parameter "name" (location)'.
        val parameterSegment = segments[1].trim()
        val marker = parameterSegment.indexOf("\" (")
        if (marker <= 0 || !parameterSegment.startsWith("parameter \"") || !parameterSegment.endsWith(")")) return null
        val name = parameterSegment.substring("parameter \"".length, marker)
        val inLocation = parameterSegment.substring(marker + 3, parameterSegment.length - 1)
        return ParameterIdentity(operation.first, operation.second, name, inLocation)
    }

    private data class ParameterIdentity(
        val method: String,
        val path: String,
        val name: String,
        val inLocation: String,
    )

    private fun findParameter(
        surface: ContractSurface,
        identity: ParameterIdentity,
    ): Parameter? =
        surface.operations
            .firstOrNull {
                it.method == identity.method &&
                    pathIdentity(it.path) == pathIdentity(identity.path)
            }?.parameters
            ?.firstOrNull { it.name == identity.name && it.`in` == identity.inLocation }

    /**
     * Whether the added required property carries a JSON Schema default
     * in ANY content type of the owning request body. The walk follows
     * the engine's location grammar backwards from the change location.
     */
    internal fun propertyHasDefault(
        change: ContractChange,
        newSurface: ContractSurface,
    ): Boolean {
        val segments = change.location.split(ARROW_SEPARATOR)
        if (segments.size < 3) return false
        val operationKey = parseOperationKey(segments[0]) ?: return false
        val operation = findOperation(newSurface, operationKey) ?: return false
        val propertyName =
            segments
                .last()
                .trim()
                .removePrefix("properties.")
                .substringBefore(" ")
        // Candidate parent schemas depend on the second segment.
        val parents = mutableListOf<SchemaNode>()
        val second = segments[1].trim()
        when {
            second.startsWith("response") ->
                operation.responses.forEach { (_, response) -> parents += response.content.values }
            second == "request body" -> operation.requestBody?.let { parents += it.content.values }
            second.startsWith("parameter") -> {
                val identity = parameterIdentityOf(change) ?: return false
                operation.parameters
                    .firstOrNull { it.name == identity.name && it.`in` == identity.inLocation }
                    ?.schema
                    ?.let { parents += it }
            }
        }
        // The property sits under the parent; the remaining segments
        // (schema/items/properties.*) navigate from the parent to it.
        val tail = segments.drop(2).dropLast(1)
        return parents.any { parent -> walkToProperty(parent, tail, propertyName)?.defaultPresent == true }
    }

    private fun walkToProperty(
        start: SchemaNode,
        tail: List<String>,
        propertyName: String,
    ): SchemaNode? {
        var node: SchemaNode? = start
        for (segment in tail) {
            val trimmed = segment.trim()
            if (trimmed == "schema") {
                // schemaDiff locations carry a "schema" marker between
                // the owner and the first properties/items segment.
            } else if (trimmed == "items") {
                node = node?.items ?: return null
            } else if (trimmed.startsWith("properties.")) {
                val name = trimmed.substringAfter("properties.")
                node = node?.properties?.get(name) ?: return null
            } else {
                return null
            }
        }
        return node?.properties?.get(propertyName)
    }

    private fun findOperation(
        surface: ContractSurface,
        key: Pair<String, String>,
    ): Operation? =
        surface.operations.firstOrNull {
            it.method == key.first && pathIdentity(it.path) == pathIdentity(key.second)
        }

    private fun parseOperationKey(s: String): Pair<String, String>? {
        val separator = s.indexOf(' ')
        if (separator <= 0) return null
        val method = s.substring(0, separator).lowercase()
        val path = s.substring(separator + 1).trim()
        if (path.isEmpty() || !path.startsWith("/")) return null
        return method to path
    }

    // --- constraint direction ------------------------------------------

    private fun hasTightenedConstraint(
        from: String,
        to: String,
    ): Boolean {
        fun fieldValue(
            summary: String,
            field: String,
        ): Double? =
            summary
                .split(", ")
                .mapNotNull { entry ->
                    if (entry.startsWith("$field: ")) {
                        entry
                            .removePrefix("$field: ")
                            .removePrefix("<")
                            .takeIf { it != "unset" }
                            ?.toDoubleOrNull()
                    } else {
                        null
                    }
                }.firstOrNull()

        data class Bound(
            val field: String,
            val tightenedWhenHigher: Boolean,
        )

        val bounds =
            listOf(
                Bound("minimum", true),
                Bound("maximum", false),
                Bound("minLength", true),
                Bound("maxLength", false),
                Bound("minItems", true),
                Bound("maxItems", false),
            )
        return bounds.any { bound ->
            val oldValue = fieldValue(from, bound.field)
            val newValue = fieldValue(to, bound.field)
            if (oldValue != null && newValue != null) {
                if (bound.tightenedWhenHigher) newValue > oldValue else newValue < oldValue
            } else {
                false
            }
        }
    }

    private const val ARROW_SEPARATOR = " → "
    private const val SEGMENT_REQUEST_BODY = " → request body"
    private const val SEGMENT_PARAMETER = " → parameter"
    private const val SEGMENT_RESPONSE = " → response"
    private const val PROPERTIES_SEGMENT = " → properties."
    private const val RENAME_CANDIDATE_REASON =
        "same-type property pair added and removed in one schema — a possible rename; human review required"
    private const val UNKNOWN_DIRECTION_REASON = "the change's direction could not be determined"
}
