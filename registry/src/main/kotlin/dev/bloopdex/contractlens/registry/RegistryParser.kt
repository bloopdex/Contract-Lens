// Registry loading adapter (Phase 3).
//
// kaml (ADR-005) decodes the YAML document into the raw registry shape;
// validateRegistry() (:core) turns it into the validated typed domain.
// Everything unparseable — malformed YAML, wrong types, unknown fields
// (kaml strict mode, a deliberate policy: future registry fields must
// come with a version bump, never be silently ignored) — is converted
// into the typed ContractError model at this boundary. Raw parser
// exceptions never leak outward.

package dev.bloopdex.contractlens.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.error.MAX_INPUT_BYTES
import dev.bloopdex.contractlens.core.registry.ConsumerRegistry
import dev.bloopdex.contractlens.core.registry.RawRegistry
import dev.bloopdex.contractlens.core.registry.validateRegistry

object RegistryParser {
    private val strictYaml =
        Yaml(
            configuration = YamlConfiguration(strictMode = true),
        )

    fun parse(
        text: String,
        source: String,
    ): ConsumerRegistry {
        if (text.length > MAX_INPUT_BYTES) {
            throw ContractError.InputTooLarge(source, text.length.toLong())
        }
        val raw =
            try {
                strictYaml.decodeFromString(RawRegistry.serializer(), text)
            } catch (e: Exception) {
                throw ContractError.RegistryInvalid("$source: ${e.message}", e)
            }
        return validateRegistry(raw, source)
    }
}
