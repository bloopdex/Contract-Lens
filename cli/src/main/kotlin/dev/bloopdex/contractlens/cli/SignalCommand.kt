// `contractlens signal <old-snapshot> <new-snapshot> [--registry <file>]
// [--output <file>]` — the DeployScore feed emitter (ADR-008).
//
// Runs the normal analysis pipeline (verify → diff → classify →
// optional impact mapping) and emits the contractlens-signal v1 payload
// to stdout (default) or a file. The payload is metadata only
// (docs/deployscore-feed.md): counts, verdicts, operation identities,
// registry-declared consumer ids — never raw contract content.
//
// Deliberately absent: any network integration. DeployScore has no
// implemented API (its API/webhook work will define the transport), so an
// unavailable or nonexistent DeployScore cannot affect local analysis
// — the analysis completes before emission.
//
// Exit codes follow the established contract: 0 success, 1 breaking
// changes detected, 2 operational error (including an unwritable
// output file).

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.bloopdex.contractlens.core.classify.Classifier
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.impact.ConsumerMapper
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.core.signal.ContractLensSignal
import dev.bloopdex.contractlens.core.signal.SignalBuilder
import dev.bloopdex.contractlens.registry.RegistryParser
import dev.bloopdex.contractlens.snapshot.parseAndVerifySnapshot
import net.logstash.logback.argument.StructuredArguments
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.toPath

class SignalCommand : BaseCommand(name = "signal") {
    override fun help(context: Context): String = "Emit the contractlens-signal v1 DeployScore feed payload (metadata only, offline)"

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
        option("--registry", help = "Path to the consumer registry (versioned YAML); adds the consumers section")
            .file(mustExist = true, canBeFile = true, mustBeReadable = true)

    private val output by
        option("--output", help = "Write the payload to this file instead of stdout")

    override fun runCommand() {
        val oldPath = old.toPath().toAbsolutePath().normalize()
        val newPath = new.toPath().toAbsolutePath().normalize()
        val oldDocument = parseAndVerifySnapshot(Files.readAllBytes(oldPath), oldPath.toString())
        val newDocument = parseAndVerifySnapshot(Files.readAllBytes(newPath), newPath.toString())
        if (oldDocument.contract != newDocument.contract) {
            throw ContractError.ContractMismatch(oldDocument.contract, newDocument.contract)
        }

        val startedAt = System.nanoTime()
        val changes = DiffEngine.diff(oldDocument.surface, newDocument.surface)
        val classification = Classifier.classify(changes, oldDocument.surface, newDocument.surface)
        breakingFound = classification.summary.breaking > 0
        val registryFile = registry // delegated properties do not smart-cast
        val impact =
            if (registryFile != null) {
                val registryPath = registryFile.toPath().toAbsolutePath().normalize()
                val consumerRegistry = RegistryParser.parse(Files.readString(registryPath), registryPath.toString())
                ConsumerMapper.map(changes, consumerRegistry, newDocument.contract)
            } else {
                null
            }
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000.0
        log.info(
            "analysis metrics",
            StructuredArguments.kv("contract_changes_detected", classification.summary.total),
            StructuredArguments.kv("breaking_changes_detected", classification.summary.breaking),
            StructuredArguments.kv("affected_consumers", impact?.impacts?.size ?: 0),
            StructuredArguments.kv("analysis_duration_ms", durationMs),
        )

        val payload =
            SignalBuilder.build(
                contract = newDocument.contract,
                oldSha = oldDocument.identity.sha,
                newSha = newDocument.identity.sha,
                classification = classification,
                impact = impact,
                analysisDurationMs = durationMs,
                analyzedAt = Instant.now().toString(),
                producerVersion = CONTRACTLENS_VERSION,
            )
        val json = CanonicalJson.encodeToString(ContractLensSignal.serializer(), payload)

        val outputFile = output // delegated properties do not smart-cast
        if (outputFile != null) {
            try {
                Files.writeString(
                    java.nio.file.Path
                        .of(outputFile),
                    json,
                )
            } catch (e: Exception) {
                throw ContractError.OutputUnwritable(outputFile, e.message ?: e.javaClass.simpleName)
            }
        } else {
            echo(json)
        }
    }
}
