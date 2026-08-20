// `contractlens diff <old-snapshot> <new-snapshot>` — the structural diff
// workflow: load two verified snapshots and print the deterministic
// structural change set (human-readable or `--json`).
//
// Exit codes follow the established contract: 0 on success (changes or
// none), 2 on operational errors. Exit 1 stays reserved for breaking
// changes — the classifier layer (a later phase) will produce it.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.classify.ClassificationSummary
import dev.bloopdex.contractlens.core.classify.ClassifiedChange
import dev.bloopdex.contractlens.core.classify.Classifier
import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.diff.additiveKinds
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.snapshot.parseAndVerifySnapshot
import kotlinx.serialization.Serializable
import net.logstash.logback.argument.StructuredArguments
import java.nio.file.Files
import kotlin.io.path.toPath

@Serializable
data class DiffReportIdentity(
    val contract: String,
    val sha: String,
)

@Serializable
data class DiffSummary(
    val total: Int,
    val added: Int,
    val removed: Int,
    val changed: Int,
)

@Serializable
data class DiffReport(
    val format: String = "contractlens-diff",
    val version: Int = 1,
    val old: DiffReportIdentity,
    val new: DiffReportIdentity,
    val summary: DiffSummary,
    val changes: List<ContractChange>,
)

/**
 * `contractlens-diff` v2 — the `--classify` extension: v1 fields plus
 * the classification (verdicts, semver labels, reasons). Plain `diff`
 * output stays exactly v1.
 */
@Serializable
data class DiffClassifiedReport(
    val format: String = "contractlens-diff",
    val version: Int = 2,
    val old: DiffReportIdentity,
    val new: DiffReportIdentity,
    val summary: DiffSummary,
    val classification: ClassificationSummary,
    val changes: List<ContractChange>,
    val classified: List<ClassifiedChange>,
)

internal val addedKinds =
    additiveKinds +
        setOf(
            ChangeKind.RESPONSE_ADDED,
            ChangeKind.REQUIRED_PROPERTY_ADDED,
        )

internal val removedKinds =
    setOf(
        ChangeKind.OPERATION_REMOVED,
        ChangeKind.PARAMETER_REMOVED,
        ChangeKind.REQUEST_BODY_REMOVED,
        ChangeKind.CONTENT_TYPE_REMOVED,
        ChangeKind.RESPONSE_REMOVED,
        ChangeKind.PROPERTY_REMOVED,
        ChangeKind.REQUIRED_PROPERTY_REMOVED,
    )

private fun summarize(changes: List<ContractChange>): DiffSummary =
    DiffSummary(
        total = changes.size,
        added = changes.count { it.kind in addedKinds },
        removed = changes.count { it.kind in removedKinds },
        changed = changes.count { it.kind !in addedKinds && it.kind !in removedKinds },
    )

internal fun humanLine(change: ContractChange): String {
    val from = change.from
    val to = change.to
    val delta =
        when {
            from != null && to != null -> " : ${from.summary} → ${to.summary}"
            from != null -> " (was ${from.summary})"
            to != null -> " (now ${to.summary})"
            else -> ""
        }
    return "  ${change.kind} ${change.location}$delta"
}

class DiffCommand : BaseCommand(name = "diff") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String = "Diff two snapshots and report the structural changes"

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

    private val jsonOut by option("--json", help = "Machine-readable JSON report on stdout").flag()

    private val classifyOut by
        option("--classify", help = "Classify changes with the breaking / non-breaking / review ruleset; exit 1 on breaking")
            .flag()

    override fun runCommand() {
        // Integrity is never bypassed: both snapshots are fully verified
        // (format version + content hash) before any diffing happens.
        val oldPath = old.toPath().toAbsolutePath().normalize()
        val newPath = new.toPath().toAbsolutePath().normalize()
        val oldDocument = parseAndVerifySnapshot(Files.readAllBytes(oldPath), oldPath.toString())
        val newDocument = parseAndVerifySnapshot(Files.readAllBytes(newPath), newPath.toString())

        val changes = DiffEngine.diff(oldDocument.surface, newDocument.surface)
        val summary = summarize(changes)

        if (classifyOut) {
            runClassified(
                oldDocument.contract,
                oldDocument.identity.sha,
                newDocument.contract,
                newDocument.identity.sha,
                changes,
                oldDocument.surface,
                newDocument.surface,
                summary,
            )
        } else if (jsonOut) {
            echo(
                CanonicalJson.encodeToString(
                    DiffReport.serializer(),
                    DiffReport(
                        old = DiffReportIdentity(oldDocument.contract, oldDocument.identity.sha),
                        new = DiffReportIdentity(newDocument.contract, newDocument.identity.sha),
                        summary = summary,
                        changes = changes,
                    ),
                ),
            )
        } else {
            echo("old: ${oldDocument.contract} @ ${oldDocument.identity.sha}")
            echo("new: ${newDocument.contract} @ ${newDocument.identity.sha}")
            echo("changes: ${summary.total} (added ${summary.added}, removed ${summary.removed}, changed ${summary.changed})")
            if (changes.isEmpty()) {
                echo("no structural changes")
            } else {
                changes.forEach { echo(humanLine(it)) }
            }
        }
    }

    private fun runClassified(
        oldContract: String,
        oldSha: String,
        newContract: String,
        newSha: String,
        changes: List<ContractChange>,
        oldSurface: dev.bloopdex.contractlens.core.model.ContractSurface,
        newSurface: dev.bloopdex.contractlens.core.model.ContractSurface,
        summary: DiffSummary,
    ) {
        val startedAt = System.nanoTime()
        val classification = Classifier.classify(changes, oldSurface, newSurface)
        breakingFound = classification.summary.breaking > 0
        log.info(
            "analysis metrics",
            StructuredArguments.kv("contract_changes_detected", summary.total),
            StructuredArguments.kv("breaking_changes_detected", classification.summary.breaking),
            StructuredArguments.kv("analysis_duration_ms", (System.nanoTime() - startedAt) / 1_000_000.0),
        )
        if (jsonOut) {
            echo(
                CanonicalJson.encodeToString(
                    DiffClassifiedReport.serializer(),
                    DiffClassifiedReport(
                        old = DiffReportIdentity(oldContract, oldSha),
                        new = DiffReportIdentity(newContract, newSha),
                        summary = summary,
                        classification = classification.summary,
                        changes = changes,
                        classified = classification.changes,
                    ),
                ),
            )
        } else {
            echo("old: $oldContract @ $oldSha")
            echo("new: $newContract @ $newSha")
            echo("changes: ${summary.total} (added ${summary.added}, removed ${summary.removed}, changed ${summary.changed})")
            echo(
                "classification: ${classification.summary.breaking} breaking, " +
                    "${classification.summary.nonBreaking} non-breaking, ${classification.summary.review} review",
            )
            echo(
                "semver: ${classification.summary.semver
                    ?.name
                    ?.lowercase() ?: "none"}",
            )
            if (classification.changes.isEmpty()) {
                echo("no structural changes")
            } else {
                for (entry in classification.changes) {
                    val semver = entry.semver?.name?.lowercase()
                    echo(
                        "  ${entry.change.kind} ${entry.change.location}${humanDelta(
                            entry.change,
                        )} [${entry.verdict.name.lowercase().replace('_', '-')}]${if (semver != null) " ($semver)" else ""}",
                    )
                    echo("    reason: ${entry.reason}")
                }
            }
        }
    }
}

internal fun humanDelta(change: ContractChange): String {
    val from = change.from
    val to = change.to
    return when {
        from != null && to != null -> " : ${from.summary} → ${to.summary}"
        from != null -> " (was ${from.summary})"
        to != null -> " (now ${to.summary})"
        else -> ""
    }
}
