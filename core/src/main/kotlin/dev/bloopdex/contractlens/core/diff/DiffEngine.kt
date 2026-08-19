// The structural diff engine (Phase 2).
//
// Two canonical contract surfaces in, one deterministic change list out.
// Pure functions over the canonical model: no IO, no CLI, no consumer
// knowledge, no verdicts (the classifier layer owns those). Matching
// uses the Phase 0 identity rules — operations by (method, normalized
// path template), parameters by (in, name), responses by normalized
// status key — never operationIds, positions, or object ordering.
//
// Nested schema differences surface as precise leaf-level changes (e.g.
// TYPE_CHANGED at "... → properties.profile → properties.address →
// properties.postalCode"), never as one generic "schema changed" event.
// Renames are NOT interpreted: a removed field and an added field are
// two independent structural facts (Phase 0: renames are review
// candidates, never auto-classified).

package dev.bloopdex.contractlens.core.diff

import dev.bloopdex.contractlens.core.model.Constraints
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.SchemaNode

object DiffEngine {

    /** Diff two canonical surfaces; the result is sorted with [changeOrder]. */
    fun diff(
        oldSurface: ContractSurface,
        newSurface: ContractSurface,
    ): List<ContractChange> {
        val old = oldSurface.canonical()
        val new = newSurface.canonical()
        val changes = mutableListOf<ContractChange>()

        val oldOps = old.operations.associateBy { it.pathIdentity to it.method }
        val newOps = new.operations.associateBy { it.pathIdentity to it.method }
        val operationKeys = (oldOps.keys + newOps.keys).sortedWith(compareBy({ it.first }, { it.second }))

        for (key in operationKeys) {
            val oldOp = oldOps[key]
            val newOp = newOps[key]
            when {
                oldOp == null -> changes += added(key, newOp!!)
                newOp == null -> changes += removed(key, oldOp)
                else -> changes += diffOperation(oldOp, newOp)
            }
        }
        return changes.sortedWith(changeOrder)
    }

    private fun added(key: Pair<String, String>, op: Operation): ContractChange =
        change(
            kind = ChangeKind.OPERATION_ADDED,
            target = ChangeTarget.OPERATION,
            location = opLocation(op),
            source = op.location,
            from = null,
            to = ChangeValue("${op.method} ${op.path}"),
        )

    private fun removed(key: Pair<String, String>, op: Operation): ContractChange =
        change(
            kind = ChangeKind.OPERATION_REMOVED,
            target = ChangeTarget.OPERATION,
            location = opLocation(op),
            source = op.location,
            from = ChangeValue("${op.method} ${op.path}"),
            to = null,
        )

    private fun diffOperation(old: Operation, new: Operation): List<ContractChange> {
        val changes = mutableListOf<ContractChange>()
        val base = opLocation(old)
        if (old.path != new.path) {
            changes += change(
                kind = ChangeKind.OPERATION_PATH_CHANGED,
                target = ChangeTarget.OPERATION,
                location = base,
                source = new.location,
                from = ChangeValue(old.path),
                to = ChangeValue(new.path),
            )
        }
        changes += diffParameters(base, old.parameters, new.parameters)
        changes += diffRequestBodies(base, old.requestBody, new.requestBody)
        changes += diffResponses(base, old.responses, new.responses)
        return changes
    }

    private fun opLocation(op: Operation): String = "${op.method.uppercase()} ${op.path}"

    // --- parameters ---------------------------------------------------

    private fun diffParameters(
        base: String,
        oldParameters: List<Parameter>,
        newParameters: List<Parameter>,
    ): List<ContractChange> {
        val changes = mutableListOf<ContractChange>()
        val oldByIdentity = oldParameters.associateBy { it.`in` to it.name }
        val newByIdentity = newParameters.associateBy { it.`in` to it.name }

        val oldOnly = (oldByIdentity.keys - newByIdentity.keys).sortedWith(compareBy({ it.first }, { it.second }))
        val newOnly = (newByIdentity.keys - oldByIdentity.keys).sortedWith(compareBy({ it.first }, { it.second }))

        // A parameter whose name moved between `in` locations is a
        // location change (structural fact: identity component changed).
        // Pair them by name, deterministically; when several candidates
        // share a name (e.g. the same name in query AND header), they
        // pair up in sorted order and any leftover stays an add/remove.
        val consumedOld = mutableSetOf<Pair<String, String>>()
        val consumedNew = mutableSetOf<Pair<String, String>>()
        val oldNames = oldOnly.map { it.second }.toSet()
        val newNames = newOnly.map { it.second }.toSet()
        for (name in (oldNames intersect newNames).sorted()) {
            val oldCandidates = oldOnly.filter { it.second == name }
            val newCandidates = newOnly.filter { it.second == name }
            for ((oldIdentity, newIdentity) in oldCandidates.zip(newCandidates)) {
                changes += diffRelocatedParameter(base, oldByIdentity.getValue(oldIdentity), newByIdentity.getValue(newIdentity))
                consumedOld += oldIdentity
                consumedNew += newIdentity
            }
        }

        for ((locationIn, name) in oldOnly.filter { it !in consumedOld }) {
            changes += change(
                kind = ChangeKind.PARAMETER_REMOVED,
                target = ChangeTarget.PARAMETER,
                location = "$base → parameter \"$name\" ($locationIn)",
                source = oldByIdentity.getValue(locationIn to name).location,
                from = ChangeValue(locationIn),
                to = null,
            )
        }
        for ((locationIn, name) in newOnly.filter { it !in consumedNew }) {
            changes += change(
                kind = ChangeKind.PARAMETER_ADDED,
                target = ChangeTarget.PARAMETER,
                location = "$base → parameter \"$name\" ($locationIn)",
                source = newByIdentity.getValue(locationIn to name).location,
                from = null,
                to = ChangeValue(locationIn),
            )
        }

        // Matched identities: requiredness and schema.
        val common = (oldByIdentity.keys intersect newByIdentity.keys).sortedWith(compareBy({ it.first }, { it.second }))
        for (identity in common) {
            val oldParam = oldByIdentity.getValue(identity)
            val newParam = newByIdentity.getValue(identity)
            val location = "$base → parameter \"${oldParam.name}\" (${oldParam.`in`})"
            if (oldParam.required != newParam.required) {
                changes += change(
                    kind = ChangeKind.PARAMETER_REQUIRED_CHANGED,
                    target = ChangeTarget.PARAMETER,
                    location = location,
                    source = newParam.location,
                    from = ChangeValue(oldParam.required.toString()),
                    to = ChangeValue(newParam.required.toString()),
                )
            }
            changes += diffParameterSchema(location, oldParam, newParam)
        }
        return changes
    }

    private fun diffRelocatedParameter(
        base: String,
        oldParam: Parameter,
        newParam: Parameter,
    ): List<ContractChange> {
        val changes = mutableListOf<ContractChange>()
        val location = "$base → parameter \"${newParam.name}\" (${newParam.`in`})"
        changes += change(
            kind = ChangeKind.PARAMETER_LOCATION_CHANGED,
            target = ChangeTarget.PARAMETER,
            location = location,
            source = newParam.location,
            from = ChangeValue(oldParam.`in`),
            to = ChangeValue(newParam.`in`),
        )
        if (oldParam.required != newParam.required) {
            changes += change(
                kind = ChangeKind.PARAMETER_REQUIRED_CHANGED,
                target = ChangeTarget.PARAMETER,
                location = location,
                source = newParam.location,
                from = ChangeValue(oldParam.required.toString()),
                to = ChangeValue(newParam.required.toString()),
            )
        }
        changes += diffParameterSchema(location, oldParam, newParam)
        return changes
    }

    private fun diffParameterSchema(
        location: String,
        oldParam: Parameter,
        newParam: Parameter,
    ): List<ContractChange> {
        val oldSchema = oldParam.schema
        val newSchema = newParam.schema
        return when {
            oldSchema != null && newSchema != null ->
                schemaDiff(oldSchema, newSchema, location, newParam.location)
            oldSchema == null && newSchema != null ->
                listOf(
                    change(
                        kind = ChangeKind.PARAMETER_SCHEMA_CHANGED,
                        target = ChangeTarget.PARAMETER,
                        location = location,
                        source = newParam.location,
                        from = ChangeValue("no schema"),
                        to = ChangeValue(typeSummary(newSchema)),
                    )
                )
            oldSchema != null && newSchema == null ->
                listOf(
                    change(
                        kind = ChangeKind.PARAMETER_SCHEMA_CHANGED,
                        target = ChangeTarget.PARAMETER,
                        location = location,
                        source = oldParam.location,
                        from = ChangeValue(typeSummary(oldSchema)),
                        to = ChangeValue("no schema"),
                    )
                )
            else -> emptyList()
        }
    }

    // --- request bodies ----------------------------------------------

    private fun diffRequestBodies(
        base: String,
        oldBody: RequestBody?,
        newBody: RequestBody?,
    ): List<ContractChange> {
        val changes = mutableListOf<ContractChange>()
        val location = "$base → request body"
        when {
            oldBody == null && newBody != null ->
                changes += change(
                    ChangeKind.REQUEST_BODY_ADDED,
                    ChangeTarget.REQUEST_BODY,
                    location,
                    null,
                    null,
                    ChangeValue("required: ${newBody.required}"),
                )
            oldBody != null && newBody == null ->
                changes += change(
                    ChangeKind.REQUEST_BODY_REMOVED,
                    ChangeTarget.REQUEST_BODY,
                    location,
                    null,
                    ChangeValue("required: ${oldBody.required}"),
                    null,
                )
            oldBody != null && newBody != null -> {
                if (oldBody.required != newBody.required) {
                    changes += change(
                        ChangeKind.REQUEST_BODY_REQUIRED_CHANGED,
                        ChangeTarget.REQUEST_BODY,
                        location,
                        null,
                        ChangeValue(oldBody.required.toString()),
                        ChangeValue(newBody.required.toString()),
                    )
                }
                changes += diffContentTypes(location, ChangeTarget.REQUEST_BODY, oldBody.content, newBody.content)
            }
        }
        return changes
    }

    // --- responses ----------------------------------------------------

    private fun diffResponses(
        base: String,
        oldResponses: Map<String, dev.bloopdex.contractlens.core.model.Response>,
        newResponses: Map<String, dev.bloopdex.contractlens.core.model.Response>,
    ): List<ContractChange> {
        val changes = mutableListOf<ContractChange>()
        val statuses = (oldResponses.keys + newResponses.keys).sorted()
        for (status in statuses) {
            val oldResponse = oldResponses[status]
            val newResponse = newResponses[status]
            val location = "$base → response $status"
            when {
                oldResponse == null -> changes +=
                    change(
                        ChangeKind.RESPONSE_ADDED,
                        ChangeTarget.RESPONSE,
                        location,
                        newResponse!!.location,
                        null,
                        ChangeValue(status),
                    )
                newResponse == null -> changes +=
                    change(
                        ChangeKind.RESPONSE_REMOVED,
                        ChangeTarget.RESPONSE,
                        location,
                        oldResponse.location,
                        ChangeValue(status),
                        null,
                    )
                else ->
                    changes += diffContentTypes(location, ChangeTarget.RESPONSE, oldResponse.content, newResponse.content)
            }
        }
        return changes
    }

    /**
     * Content-type maps belong to either a request body or a response;
     * the [target] keeps that distinction explicit in the change.
     * [ownerLocation] is the full owner location, INCLUDING the response
     * status when [target] is RESPONSE (e.g. "GET /users → response 200").
     */
    private fun diffContentTypes(
        ownerLocation: String,
        target: ChangeTarget,
        oldContent: Map<String, SchemaNode>,
        newContent: Map<String, SchemaNode>,
    ): List<ContractChange> {
        val changes = mutableListOf<ContractChange>()
        val contentTypes = (oldContent.keys + newContent.keys).sorted()
        for (contentType in contentTypes) {
            val oldSchema = oldContent[contentType]
            val newSchema = newContent[contentType]
            val location = "$ownerLocation (content $contentType)"
            when {
                oldSchema == null -> changes +=
                    change(
                        ChangeKind.CONTENT_TYPE_ADDED,
                        target,
                        location,
                        null,
                        null,
                        ChangeValue(contentType),
                    )
                newSchema == null -> changes +=
                    change(
                        ChangeKind.CONTENT_TYPE_REMOVED,
                        target,
                        location,
                        null,
                        ChangeValue(contentType),
                        null,
                    )
                else ->
                    changes += schemaDiff(oldSchema, newSchema, "$ownerLocation → schema", newSchema.location)
            }
        }
        return changes
    }

    // --- schemas ------------------------------------------------------

    private fun schemaDiff(
        old: SchemaNode,
        new: SchemaNode,
        location: String,
        source: String,
    ): List<ContractChange> {
        if (old == new) return emptyList()
        val changes = mutableListOf<ContractChange>()

        if (old.types != new.types) {
            changes += change(
                ChangeKind.TYPE_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                ChangeValue(typeSummary(old)),
                ChangeValue(typeSummary(new)),
            )
        } else if (old.nodeType != new.nodeType) {
            changes += change(
                ChangeKind.TYPE_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                ChangeValue(old.nodeType.name),
                ChangeValue(new.nodeType.name),
            )
        }

        if (old.nullable != new.nullable) {
            changes += change(
                ChangeKind.NULLABLE_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                ChangeValue(old.nullable.toString()),
                ChangeValue(new.nullable.toString()),
            )
        }

        if (old.enumValues != new.enumValues) {
            changes += change(
                ChangeKind.ENUM_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                ChangeValue(old.enumValues.joinToString(", ")),
                ChangeValue(new.enumValues.joinToString(", ")),
            )
        }

        if (old.defaultPresent != new.defaultPresent) {
            changes += change(
                ChangeKind.DEFAULT_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                ChangeValue(if (old.defaultPresent) "has default" else "no default"),
                ChangeValue(if (new.defaultPresent) "has default" else "no default"),
            )
        }

        if (old.refTarget != null && new.refTarget != null && old.refTarget != new.refTarget) {
            changes += change(
                ChangeKind.REF_TARGET_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                ChangeValue(old.refTarget),
                ChangeValue(new.refTarget),
            )
        }

        if (old.constraints != new.constraints) {
            val summary = constraintsDiffSummary(old.constraints, new.constraints)
            changes += change(
                ChangeKind.CONSTRAINT_CHANGED,
                ChangeTarget.SCHEMA,
                location,
                source,
                summary.first,
                summary.second,
            )
        }

        val oldProperties = old.properties
        val newProperties = new.properties
        val addedProperties = (newProperties.keys - oldProperties.keys).sorted()
        val removedProperties = (oldProperties.keys - newProperties.keys).sorted()
        for (name in removedProperties) {
            val child = oldProperties.getValue(name)
            changes += change(
                ChangeKind.PROPERTY_REMOVED,
                ChangeTarget.SCHEMA,
                "$location → properties.$name",
                child.location,
                ChangeValue(typeSummary(child)),
                null,
            )
        }
        for (name in addedProperties) {
            val child = newProperties.getValue(name)
            changes += change(
                ChangeKind.PROPERTY_ADDED,
                ChangeTarget.SCHEMA,
                "$location → properties.$name",
                child.location,
                null,
                ChangeValue(typeSummary(child)),
            )
        }
        val commonProperties = (oldProperties.keys intersect newProperties.keys).sorted()
        for (name in commonProperties) {
            val oldChild = oldProperties.getValue(name)
            val newChild = newProperties.getValue(name)
            changes += schemaDiff(oldChild, newChild, "$location → properties.$name", newChild.location)
        }

        val addedRequired = (new.required - old.required.toSet()).sorted()
        val removedRequired = (old.required - new.required.toSet()).sorted()
        for (name in removedRequired) {
            changes += change(
                ChangeKind.REQUIRED_PROPERTY_REMOVED,
                ChangeTarget.SCHEMA,
                "$location → properties.$name",
                source,
                ChangeValue("required"),
                ChangeValue("optional"),
            )
        }
        for (name in addedRequired) {
            changes += change(
                ChangeKind.REQUIRED_PROPERTY_ADDED,
                ChangeTarget.SCHEMA,
                "$location → properties.$name",
                source,
                ChangeValue("optional"),
                ChangeValue("required"),
            )
        }

        when {
            old.items != null && new.items != null ->
                changes += schemaDiff(old.items, new.items, "$location → items", new.items.location)
            old.items == null && new.items != null ->
                changes += change(
                    ChangeKind.ITEMS_CHANGED,
                    ChangeTarget.SCHEMA,
                    location,
                    source,
                    ChangeValue("no item schema"),
                    ChangeValue(typeSummary(new.items)),
                )
            old.items != null && new.items == null ->
                changes += change(
                    ChangeKind.ITEMS_CHANGED,
                    ChangeTarget.SCHEMA,
                    location,
                    source,
                    ChangeValue(typeSummary(old.items)),
                    ChangeValue("no item schema"),
                )
        }
        return changes
    }

    // --- summaries ----------------------------------------------------

    private fun typeSummary(node: SchemaNode): String =
        if (node.types.isEmpty()) {
            if (node.nodeType == NodeType.ANY) "any" else node.nodeType.name.lowercase()
        } else {
            node.types.joinToString("|")
        }

    /**
     * Deterministic per-field constraint summaries covering exactly the
     * fields that changed (sorted by field name), e.g.
     * from = "minimum: 1.0, pattern: A" to = "minimum: 10.0, pattern: B".
     */
    private fun constraintsDiffSummary(
        old: Constraints?,
        new: Constraints?,
    ): Pair<ChangeValue?, ChangeValue?> {
        data class FieldDiff(val name: String, val oldValue: String?, val newValue: String?)

        val diffs =
            listOf(
                FieldDiff("minimum", old?.minimum?.toString(), new?.minimum?.toString()),
                FieldDiff("maximum", old?.maximum?.toString(), new?.maximum?.toString()),
                FieldDiff("minLength", old?.minLength?.toString(), new?.minLength?.toString()),
                FieldDiff("maxLength", old?.maxLength?.toString(), new?.maxLength?.toString()),
                FieldDiff("pattern", old?.pattern, new?.pattern),
                FieldDiff("minItems", old?.minItems?.toString(), new?.minItems?.toString()),
                FieldDiff("maxItems", old?.maxItems?.toString(), new?.maxItems?.toString()),
            ).filter { it.oldValue != it.newValue }
        if (diffs.isEmpty()) return null to null
        val from = ChangeValue(diffs.joinToString(", ") { "${it.name}: ${it.oldValue ?: "<unset>"}" })
        val to = ChangeValue(diffs.joinToString(", ") { "${it.name}: ${it.newValue ?: "<unset>"}" })
        return from to to
    }

    private fun change(
        kind: ChangeKind,
        target: ChangeTarget,
        location: String,
        source: String?,
        from: ChangeValue?,
        to: ChangeValue?,
    ): ContractChange = ContractChange(
        kind = kind,
        target = target,
        location = location,
        sourceLocation = source,
        from = from,
        to = to,
        explanation = explainChange(kind, location, from, to),
    )
}
