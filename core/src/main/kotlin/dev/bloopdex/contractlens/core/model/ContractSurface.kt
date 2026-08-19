// Canonical contract model (Phase 0, ADR-001/ADR-004).
//
// One format-neutral representation of a contract surface; OpenAPI maps
// into it today, GraphQL/JSON Schema later via their own parsers. Every
// node carries a `location` — a document-pointer-style path back into
// the source contract — because reports must quote the schema path,
// never an internal id.
//
// All collections are normalized at construction time (sorted, deduped,
// deterministic); see Normalization.kt. Deliberate omissions (Phase 0
// scope): descriptions, examples, deprecation flags, security schemes,
// servers — they do not affect structural diffing or classification and
// can be added additively if a later phase needs them.

package dev.bloopdex.contractlens.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ContractSurface(
    /** The contract's logical name (CLI default: the source file stem). */
    val name: String,
    /** Format marker; "openapi" today (ADR-004). */
    val kind: String,
    /** The source format's own version, e.g. "3.0.3" / "3.1.0". */
    val formatVersion: String,
    /** Sorted by (pathIdentity, method). */
    val operations: List<Operation>,
) {
    /**
     * Defensive canonical rebuild: re-sorts and re-normalizes every
     * collection. Idempotent — canonical(canonical(x)) == canonical(x)
     * is pinned by property tests.
     */
    fun canonical(): ContractSurface = copy(operations = operations.map { it.canonical() }.sortedWith(operationOrder))
}

@Serializable
data class Operation(
    /** Lowercase HTTP method as written in the source contract. */
    val method: String,
    /** Path template exactly as written in the source contract. */
    val path: String,
    /** Identity form of the template: every {param} replaced by {} (ADR-001). */
    val pathIdentity: String,
    /** Sorted by (in, name). Includes parameters inherited from the path item. */
    val parameters: List<Parameter>,
    val requestBody: RequestBody?,
    /** Sorted by normalized status key ("2xx", "200", "default", ...). */
    val responses: Map<String, Response>,
    /** e.g. paths./users/{id}.get */
    val location: String,
) {
    fun canonical(): Operation = copy(
        parameters = parameters.map { it.canonical() }.sortedWith(compareBy({ it.in }, { it.name })),
        requestBody = requestBody?.canonical(),
        responses = responses.toSortedMap().mapValues { it.value.canonical() },
    )
}

@Serializable
data class Parameter(
    val name: String,
    /** One of: path, query, header, cookie. */
    val `in`: String,
    val required: Boolean,
    val schema: SchemaNode?,
    val location: String,
) {
    fun canonical(): Parameter = copy(schema = schema?.canonical())
}

@Serializable
data class RequestBody(
    val required: Boolean,
    /** Content type -> schema, sorted by content type. */
    val content: Map<String, SchemaNode>,
) {
    fun canonical(): RequestBody = copy(content = content.toSortedMap().mapValues { it.value.canonical() })
}

@Serializable
data class Response(
    /** Content type -> schema, sorted by content type. */
    val content: Map<String, SchemaNode>,
    val location: String,
) {
    fun canonical(): Response = copy(content = content.toSortedMap().mapValues { it.value.canonical() })
}

@Serializable
enum class NodeType { SCALAR, OBJECT, ARRAY, ENUM, REF, ANY }

@Serializable
data class Constraints(
    val minimum: Double? = null,
    val maximum: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
)

@Serializable
data class SchemaNode(
    val nodeType: NodeType,
    /** Non-null JSON types, sorted; empty = unspecified (ANY). */
    val types: List<String>,
    val format: String?,
    /** OBJECT only: property name -> schema, sorted by name. */
    val properties: Map<String, SchemaNode>,
    /** Sorted, deduped; every entry names a key in `properties`. */
    val required: List<String>,
    /** ARRAY only. */
    val items: SchemaNode?,
    /** ENUM only: stringified values, document order (deterministic from input). */
    val enumValues: List<String>,
    /** Normalized from 3.0 `nullable: true` and 3.1 "null" in `type`. */
    val nullable: Boolean,
    /** REF only: the target pointer, e.g. #/components/schemas/User (ADR-001: resolved for comparison, name preserved for explanation). */
    val refTarget: String?,
    val constraints: Constraints?,
    /** Whether the schema declares a default value (matters to Phase 2 classification: ADR-001). */
    val defaultPresent: Boolean,
    val location: String,
) {
    fun canonical(): SchemaNode = copy(
        types = types.sorted(),
        properties = properties.toSortedMap().mapValues { it.value.canonical() },
        required = required.sorted().distinct(),
        items = items?.canonical(),
    )
}
