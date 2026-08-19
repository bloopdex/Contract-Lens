// Generated-client projection (Phase 4, ADR-006 on the Phase 4 page).
//
// ADR-006 decision: diff the SOURCE OF TRUTH with generator knowledge —
// the generated client is a deterministic function of the OpenAPI spec,
// so this adapter projects a canonical contract surface into the shape
// a generated client exposes (method names, merged request objects,
// normalized return type) and feeds that projection to the SHARED
// Phase 2 diff engine. Parsing generated TS/Java/Kotlin OUTPUT was
// rejected: cross-language parsing is fragile and duplicates the
// static-analysis territory Phase 0 assigned to BlastRadius.
//
// Projection rules (deterministic, style-stable at this depth):
//   - every operation becomes a client method: "client.<name>" where
//     name = lowercase(verb) + PascalCase(path segments), with {param}
//     segments contributing "By<PascalCase(param)>"
//   - request parameters merge into one request object schema (required
//     when any parameter or the body is required); the OpenAPI request
//     body becomes its "body" property
//   - the return schema is the schema of the first (sorted) response
//     status's first (sorted) content type — "application/json"
//     preferred; no responses -> a void (ANY) return schema
//   - TS / Kotlin / Java styles share these conventions today; the style
//     is recorded in the surface and reserved for generator-specific
//     rules later
//
// Known limitations (documented, honest groundwork): the canonical model
// resolves $refs inline, so schemas surface at their use sites and model
// TYPE names are not reported — only method-level and inline schema
// changes. This projection is convention-stable, not byte-exact against
// any generator version.

package dev.bloopdex.contractlens.generated

import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GeneratorStyle {
    @SerialName("ts")
    TYPESCRIPT,

    @SerialName("kotlin")
    KOTLIN,

    @SerialName("java")
    JAVA,
}

/** The style's wire name ("ts" / "kotlin" / "java") — also the projected surface's formatVersion. */
val GeneratorStyle.wireName: String
    get() =
        when (this) {
            GeneratorStyle.TYPESCRIPT -> "ts"
            GeneratorStyle.KOTLIN -> "kotlin"
            GeneratorStyle.JAVA -> "java"
        }

object GeneratedClientProjection {
    fun project(
        surface: ContractSurface,
        style: GeneratorStyle,
    ): ContractSurface =
        ContractSurface(
            name = surface.name,
            kind = "generated-client",
            formatVersion = style.wireName,
            operations =
                surface.operations
                    .map(::projectOperation)
                    .sortedWith(compareBy({ it.pathIdentity }, { it.method }, { it.path })),
        )

    private fun projectOperation(operation: Operation): Operation {
        val methodName = generatedMethodName(operation.method, operation.path)
        val requestSchema = requestSchemaOf(operation)
        val returnSchema = returnSchemaOf(operation)
        // The projected operation keeps the ORIGINAL HTTP verb as its
        // method so engine locations read "GET client.getUsersById" —
        // the client path carries the generated identity.
        return Operation(
            method = operation.method,
            path = "client.$methodName",
            pathIdentity = "client.$methodName",
            parameters = emptyList(),
            requestBody =
                RequestBody(
                    required = requestSchema.required.isNotEmpty(),
                    content = mapOf("application/json" to requestSchema),
                ),
            responses =
                mapOf(
                    "return" to
                        Response(
                            content = mapOf("application/json" to returnSchema),
                            location = "generated-client.$methodName.return",
                        ),
                ),
            location = "generated-client.$methodName",
        )
    }

    internal fun generatedMethodName(
        method: String,
        path: String,
    ): String =
        method.lowercase() +
            path
                .split('/')
                .filter { it.isNotEmpty() }
                .joinToString("") { segment ->
                    val trimmed = segment.removePrefix("{").removeSuffix("}")
                    if (segment != trimmed) "By${pascalCase(trimmed)}" else pascalCase(trimmed)
                }

    private fun pascalCase(value: String): String =
        value
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { segment -> segment.replaceFirstChar { it.uppercase() } }

    private fun requestSchemaOf(operation: Operation): SchemaNode {
        val properties = LinkedHashMap<String, SchemaNode>()
        val required = mutableListOf<String>()
        for (parameter in operation.parameters.sortedWith(compareBy({ it.`in` }, { it.name }))) {
            properties[parameter.name] = parameter.schema ?: anyNode("parameter.${parameter.name}")
            if (parameter.required) required += parameter.name
        }
        operation.requestBody?.let { body ->
            properties["body"] =
                body.content
                    .toSortedMap()
                    .values
                    .first()
            if (body.required) required += "body"
        }
        return SchemaNode(
            nodeType = NodeType.OBJECT,
            types = listOf("object"),
            format = null,
            properties = properties,
            required = required,
            items = null,
            enumValues = emptyList(),
            nullable = false,
            refTarget = null,
            constraints = null,
            defaultPresent = false,
            location = "request",
        )
    }

    private fun returnSchemaOf(operation: Operation): SchemaNode {
        if (operation.responses.isEmpty()) return anyNode("void")
        val content =
            operation.responses
                .toSortedMap()
                .values
                .first()
                .content
                .toSortedMap()
        return content["application/json"] ?: content.values.first()
    }

    private fun anyNode(location: String): SchemaNode =
        SchemaNode(
            nodeType = NodeType.ANY,
            types = emptyList(),
            format = null,
            properties = emptyMap(),
            required = emptyList(),
            items = null,
            enumValues = emptyList(),
            nullable = false,
            refTarget = null,
            constraints = null,
            defaultPresent = false,
            location = location,
        )
}
