// Usage-graph loading adapter (Phase 4 groundwork). Same boundary as
// RegistryParser: kaml strict-mode decode into the raw shape, then
// validateUsageGraph() (:core) into the validated typed domain. Raw
// parser exceptions never leak outward.

package dev.bloopdex.contractlens.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.usage.RawUsageGraph
import dev.bloopdex.contractlens.core.usage.UsageGraph
import dev.bloopdex.contractlens.core.usage.validateUsageGraph

object UsageParser {
    private val strictYaml =
        Yaml(
            configuration = YamlConfiguration(strictMode = true),
        )

    fun parse(
        text: String,
        source: String,
    ): UsageGraph {
        val raw =
            try {
                strictYaml.decodeFromString(RawUsageGraph.serializer(), text)
            } catch (e: Exception) {
                throw ContractError.UsageInvalid("$source: ${e.message}", e)
            }
        return validateUsageGraph(raw, source)
    }
}
