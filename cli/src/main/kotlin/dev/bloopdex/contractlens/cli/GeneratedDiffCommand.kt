// `contractlens generated-diff <old-snapshot> <new-snapshot> --style ts|kotlin|java`
// — the generated-client workflow (ADR-006): both snapshots are
// projected through deterministic generator conventions into generated
// client surfaces (client method names, merged request objects,
// normalized return type) and diffed with the SHARED structural diff engine.
// `--classify` runs the classifier over the projected change set
// (exit 1 on breaking); plain output is structural only.
//
// Honesty: the projection is convention-stable generator knowledge, not
// byte-exact generator output, and not parsed generated source.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.classify.ClassificationSummary
import dev.bloopdex.contractlens.core.classify.ClassifiedChange
import dev.bloopdex.contractlens.core.classify.Classifier
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.generated.GeneratedClientProjection
import dev.bloopdex.contractlens.generated.GeneratorStyle
import dev.bloopdex.contractlens.snapshot.parseAndVerifySnapshot
import kotlinx.serialization.Serializable
import net.logstash.logback.argument.StructuredArguments
import java.nio.file.Files
import kotlin.io.path.toPath

@Serializable
data class GeneratedDiffReport(
    val format: String = "contractlens-generated-diff",
    val version: Int = 1,
    val style: String,
    val old: DiffReportIdentity,
    val new: DiffReportIdentity,
    val summary: DiffSummary,
    val changes: List<ContractChange>,
    val classification: ClassificationSummary? = null,
    val classified: List<ClassifiedChange>? = null,
)

class GeneratedDiffCommand : BaseCommand(name = "generated-diff") {
    override fun help(context: Context): String =
        "Diff two snapshots as generated-client surfaces (generated-diff <old-snapshot> <new-snapshot> --style ts|kotlin|java)"

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

    private val style by
        option("--style", help = "Generated client style: ts, kotlin, or java")
            .choice("ts", "kotlin", "java")
            .default("ts")

    private val jsonOut by option("--json", help = "Machine-readable JSON report on stdout").flag()

    private val classifyOut by
        option("--classify", help = "Classify changes with the breaking / non-breaking / review ruleset; exit 1 on breaking")
            .flag()

    override fun runCommand() {
        val oldPath = old.toPath().toAbsolutePath().normalize()
        val newPath = new.toPath().toAbsolutePath().normalize()
        val oldDocument = parseAndVerifySnapshot(Files.readAllBytes(oldPath), oldPath.toString())
        val newDocument = parseAndVerifySnapshot(Files.readAllBytes(newPath), newPath.toString())
        val generatorStyle =
            when (style) {
                "ts" -> GeneratorStyle.TYPESCRIPT
                "kotlin" -> GeneratorStyle.KOTLIN
                else -> GeneratorStyle.JAVA
            }
        val startedAt = System.nanoTime()
        val projectedOld = GeneratedClientProjection.project(oldDocument.surface, generatorStyle)
        val projectedNew = GeneratedClientProjection.project(newDocument.surface, generatorStyle)
        val changes = DiffEngine.diff(projectedOld, projectedNew)
        val summary =
            DiffSummary(
                total = changes.size,
                added = changes.count { it.kind in addedKinds },
                removed = changes.count { it.kind in removedKinds },
                changed = changes.count { it.kind !in addedKinds && it.kind !in removedKinds },
            )
        val classification = if (classifyOut) Classifier.classify(changes, projectedOld, projectedNew) else null
        log.info(
            "analysis metrics",
            StructuredArguments.kv("contract_changes_detected", summary.total),
            StructuredArguments.kv("breaking_changes_detected", classification?.summary?.breaking ?: 0),
            StructuredArguments.kv("analysis_duration_ms", (System.nanoTime() - startedAt) / 1_000_000.0),
        )
        if (classification != null) {
            breakingFound = classification.summary.breaking > 0
        }
        if (jsonOut) {
            echo(
                CanonicalJson.encodeToString(
                    GeneratedDiffReport.serializer(),
                    GeneratedDiffReport(
                        style = style,
                        old = DiffReportIdentity(oldDocument.contract, oldDocument.identity.sha),
                        new = DiffReportIdentity(newDocument.contract, newDocument.identity.sha),
                        summary = summary,
                        changes = changes,
                        classification = classification?.summary,
                        classified = classification?.changes,
                    ),
                ),
            )
        } else {
            echo("generated client ($style)")
            echo("old: ${oldDocument.contract} @ ${oldDocument.identity.sha}")
            echo("new: ${newDocument.contract} @ ${newDocument.identity.sha}")
            echo("changes: ${summary.total} (added ${summary.added}, removed ${summary.removed}, changed ${summary.changed})")
            if (changes.isEmpty()) {
                echo("no structural changes")
            } else if (classification == null) {
                changes.forEach { echo("  ${it.kind} ${it.location}${humanDelta(it)}") }
            } else {
                echo(
                    "classification: ${classification.summary.breaking} breaking, " +
                        "${classification.summary.nonBreaking} non-breaking, ${classification.summary.review} review",
                )
                echo(
                    "semver: ${classification.summary.semver
                        ?.name
                        ?.lowercase() ?: "none"}",
                )
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
