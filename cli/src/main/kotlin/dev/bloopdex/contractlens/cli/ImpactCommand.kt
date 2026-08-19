// `contractlens impact <old-snapshot> <new-snapshot> --registry <file>`
// — the Phase 3 workflow: verify both snapshots, diff them, load and
// validate the consumer registry, and map the change set to DECLARED
// consumers.
//
// Honesty boundary: "affected" means "this consumer declares consumption
// of the changed surface". Unregistered consumers are not visible to
// ContractLens, unmapped changes stay visible in the report, and no
// breaking verdicts are decided here — exit 1 stays reserved for the
// classifier (Phase 0 architecture contract).

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.impact.ConsumerImpact
import dev.bloopdex.contractlens.core.impact.ConsumerMapper
import dev.bloopdex.contractlens.core.impact.ImpactedChange
import dev.bloopdex.contractlens.core.impact.ImpactedOperation
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.registry.RegistryParser
import dev.bloopdex.contractlens.snapshot.parseAndVerifySnapshot
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.path.toPath

@Serializable
data class ImpactSummary(
    val changes: Int,
    val affectedConsumers: Int,
    val mappedChanges: Int,
)

@Serializable
data class ImpactJsonReport(
    val format: String = "contractlens-impact",
    val version: Int = 1,
    val old: DiffReportIdentity,
    val new: DiffReportIdentity,
    val registry: String,
    val summary: ImpactSummary,
    val changes: List<ContractChange>,
    val impacts: List<ConsumerImpact>,
    val note: String = "unregistered consumers are not visible to ContractLens",
)

private fun humanDelta(change: ContractChange): String {
    val from = change.from
    val to = change.to
    return when {
        from != null && to != null -> " : ${from.summary} → ${to.summary}"
        from != null -> " (was ${from.summary})"
        to != null -> " (now ${to.summary})"
        else -> ""
    }
}

class ImpactCommand : BaseCommand(name = "impact") {
    override fun help(context: Context): String =
        "Map diff changes to registered consumers (impact <old-snapshot> <new-snapshot> --registry <registry.yaml>)"

    private val old by
        argument(
            name = "old-snapshot",
            help = "Path to the old .snapshot.json file",
        ).file(mustExist = true, canBeFile = true, mustBeReadable = true)

    private val new by
        argument(
            name = "new-snapshot",
            help = "Path to the new .snapshot.json file",
        ).file(mustExist = true, canBeFile = true, mustBeReadable = true)

    private val registry by
        option("--registry", help = "Path to the consumer registry (versioned YAML)")
            .file(mustExist = true, canBeFile = true, mustBeReadable = true)
            .required()

    private val jsonOut by option("--json", help = "Machine-readable JSON report on stdout").flag()

    override fun runCommand() {
        val oldPath = old.toPath().toAbsolutePath().normalize()
        val newPath = new.toPath().toAbsolutePath().normalize()
        val registryPath = registry.toPath().toAbsolutePath().normalize()

        // Integrity is never bypassed: both snapshots are fully verified
        // (format version + content hash) before any diffing happens.
        val oldDocument = parseAndVerifySnapshot(Files.readAllBytes(oldPath), oldPath.toString())
        val newDocument = parseAndVerifySnapshot(Files.readAllBytes(newPath), newPath.toString())

        // Impact mapping describes the EVOLUTION of one contract; two
        // different contract names would make "the contract being
        // diffed" ambiguous (which contract do the selectors name?).
        if (oldDocument.contract != newDocument.contract) {
            throw ContractError.ContractMismatch(oldDocument.contract, newDocument.contract)
        }

        val changes = DiffEngine.diff(oldDocument.surface, newDocument.surface)
        val consumerRegistry = RegistryParser.parse(Files.readString(registryPath), registryPath.toString())
        val report = ConsumerMapper.map(changes, consumerRegistry, newDocument.contract)

        val summary =
            ImpactSummary(
                changes = report.changes.size,
                affectedConsumers = report.impacts.size,
                mappedChanges = ConsumerMapper.mappedChangeCount(report.impacts),
            )
        val registeredForContract = consumerRegistry.consumers.count { it.contract == report.contract }

        if (jsonOut) {
            echo(
                CanonicalJson.encodeToString(
                    ImpactJsonReport.serializer(),
                    ImpactJsonReport(
                        old = DiffReportIdentity(oldDocument.contract, oldDocument.identity.sha),
                        new = DiffReportIdentity(newDocument.contract, newDocument.identity.sha),
                        registry = registryPath.toString(),
                        summary = summary,
                        changes = report.changes,
                        impacts = report.impacts,
                    ),
                ),
            )
        } else {
            echo("contract: ${report.contract}")
            echo("old: ${oldDocument.contract} @ ${oldDocument.identity.sha}")
            echo("new: ${newDocument.contract} @ ${newDocument.identity.sha}")
            echo("registry: $registryPath")
            echo("changes: ${summary.changes}")
            echo("registered consumers: $registeredForContract")
            echo("affected consumers: ${summary.affectedConsumers}")
            echo("mapped changes: ${summary.mappedChanges}")
            echo("")
            for (impact in report.impacts) {
                echo("consumer ${impact.consumer.id} (${impact.consumer.kind.name.lowercase()})")
                for ((operation, impacted) in groupedByOperation(impact)) {
                    echo("  ${operation.method.uppercase()} ${operation.path}")
                    for (entry in impacted) {
                        echo("    ${entry.change.kind} ${entry.change.location}${humanDelta(entry.change)}")
                        echo("    reason: ${entry.reason}")
                    }
                }
            }
            val mappedChanges = report.impacts.flatMap { it.changes.map { entry -> entry.change } }.toSet()
            val unmapped = report.changes.filterNot { it in mappedChanges }
            if (unmapped.isNotEmpty()) {
                echo("unmapped changes: ${unmapped.size}")
                for (change in unmapped) {
                    echo("  ${change.kind} ${change.location}")
                }
                echo("  (no registered consumer declares these operations)")
            }
            echo("note: unregistered consumers are not visible to ContractLens.")
        }
    }

    /** Deterministic grouping: operations sorted by canonical identity; changes keep changeOrder. */
    private fun groupedByOperation(impact: ConsumerImpact): List<Pair<ImpactedOperation, List<ImpactedChange>>> {
        val groups = LinkedHashMap<ImpactedOperation, MutableList<ImpactedChange>>()
        for (entry in impact.changes) {
            groups.getOrPut(entry.operation) { mutableListOf() } += entry
        }
        val operationOrder =
            Comparator
                .comparing<ImpactedOperation, String> { it.pathIdentity }
                .thenComparing { it.method }
                .thenComparing { it.path }
        return groups.keys.sortedWith(operationOrder).map { it to groups.getValue(it) }
    }
}
