// The OpenAPI -> canonical model adapter.
//
// swagger-parser is an internal implementation detail of this module:
// its object model never leaks past this boundary — the rest of the
// application only ever sees ContractSurface (ADR-005). The adapter is
// responsible for version validation (before the parser can auto-convert
// Swagger 2.0 into 3.0 and mask the version) and for the conversion
// itself, including $ref resolution with cycle and depth guards.

package dev.bloopdex.contractlens.openapi

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.ContractSurface
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

class OpenApiParser {
    /** Parse an OpenAPI 3.0/3.1 document into the canonical model. */
    fun parse(
        path: Path,
        contractName: String,
    ): ContractSurface {
        val text = readText(path)
        val version = rawVersion(text)
        val result =
            try {
                val options = ParseOptions()
                options.isResolve = false
                options.isResolveFully = false
                options.isFlatten = false
                OpenAPIParser().readContents(text, null, options)
            } catch (e: Exception) {
                throw ContractError.MalformedDocument(detailOf(e), e)
            }
        val openApi =
            result.openAPI
                ?: throw ContractError.MalformedDocument(result.messages?.joinToString("; ") ?: "empty document")
        // Convert FIRST: the converter owns precise, location-carrying
        // errors (e.g. UnresolvedReference for a $ref the parser merely
        // reports as a validation message). Parser validation messages
        // only surface when conversion succeeded — structural problems
        // the canonical model itself cannot see.
        val surface = Converter(contractName, version).convert(openApi)
        if (result.messages?.isNotEmpty() == true) {
            throw ContractError.InvalidStructure(result.messages.joinToString("; "))
        }
        return surface
    }

    private fun readText(path: Path): String {
        if (!Files.exists(path)) throw ContractError.FileNotFound(path.toString())
        return try {
            Files.readString(path)
        } catch (e: Exception) {
            throw ContractError.UnreadableFile(path.toString(), e)
        }
    }

    /**
     * Validate the declared version BEFORE swagger-parser sees the
     * document: swagger-parser silently converts Swagger 2.0 to 3.0,
     * which would let an unsupported document through as if it were
     * OpenAPI 3.0.
     */
    private fun rawVersion(text: String): String {
        val root =
            try {
                val loaderOptions = LoaderOptions()
                loaderOptions.isAllowDuplicateKeys = false
                Yaml(SafeConstructor(loaderOptions)).load<Any?>(text)
            } catch (e: Exception) {
                throw ContractError.MalformedDocument(detailOf(e), e)
            }
        if (root !is Map<*, *>) {
            throw ContractError.InvalidStructure("the document root must be an object")
        }
        if (root.containsKey("swagger")) {
            throw ContractError.UnsupportedVersion("${root["swagger"]} (Swagger)")
        }
        val version =
            root["openapi"]?.toString()
                ?: throw ContractError.InvalidStructure("no 'openapi' version field found")
        if (!(version.startsWith("3.0.") || version.startsWith("3.1."))) {
            throw ContractError.UnsupportedVersion(version)
        }
        return version
    }

    private fun detailOf(e: Exception): String = e.message ?: e.javaClass.simpleName
}
