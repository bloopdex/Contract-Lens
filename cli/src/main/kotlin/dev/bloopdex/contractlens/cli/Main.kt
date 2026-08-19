// contractlens CLI entry point.
//
// Exit code contract (Architecture page, Phase 0):
//   0 - success (no breaking changes, or no classification requested)
//   1 - breaking changes detected (produced by the Phase 4 classifier:
//       `diff --classify` and `impact`)
//   2 - operational/error condition (bad usage, bad input, IO errors,
//       corrupt snapshots)
//
// Clikt's `parse` throws on failures without exiting, so the mapping to
// the contract's exit codes happens here: usage errors (CliktError,
// status 1) are remapped to 2. Commands signal breaking changes by
// setting BaseCommand.breakingFound, which main() maps to exit 1.

package dev.bloopdex.contractlens.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.bloopdex.contractlens.core.error.ContractError
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

const val EXIT_OK = 0
const val EXIT_BREAKING = 1
const val EXIT_ERROR = 2

/** Base command: --verbose turns on debug-level structured logging (stderr). */
abstract class BaseCommand(
    name: String,
) : CliktCommand(name = name) {
    private val verbose by
        option("-v", "--verbose", help = "Enable debug-level structured logs on stderr")
            .flag()

    /** Set by commands that classify changes; main() maps it to exit 1. */
    var breakingFound = false

    /** Structured metrics (Phase 5) go through the JSON log encoder on stderr. */
    protected val log = LoggerFactory.getLogger(javaClass)

    override fun run() {
        if (verbose) {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.DEBUG
        }
        runCommand()
    }

    abstract fun runCommand()
}

class ContractLensCommand : NoOpCliktCommand(name = "contractlens") {
    override fun help(context: Context): String = "Local-first API contract impact analysis"
}

fun main(args: Array<String>) {
    val diffCommand = DiffCommand()
    val impactCommand = ImpactCommand()
    val generatedDiffCommand = GeneratedDiffCommand()
    val command =
        ContractLensCommand().subcommands(
            SnapshotCommand(),
            VerifyCommand(),
            ListCommand(),
            diffCommand,
            impactCommand,
            generatedDiffCommand,
        )
    try {
        command.parse(args)
    } catch (e: PrintHelpMessage) {
        // --help: the help text has been printed; nothing to add.
    } catch (e: CliktError) {
        // Usage errors are operational: the contract reserves exit 1 for
        // breaking changes (Phase 4 classifier). Clikt has already printed the message.
        exitProcess(EXIT_ERROR)
    } catch (e: ContractError) {
        System.err.println("error [${e.code}]: ${e.message}")
        exitProcess(EXIT_ERROR)
    } catch (e: Exception) {
        System.err.println("error [INTERNAL]: ${e.message ?: e.javaClass.simpleName}")
        exitProcess(EXIT_ERROR)
    }
    if (diffCommand.breakingFound || impactCommand.breakingFound || generatedDiffCommand.breakingFound) {
        exitProcess(EXIT_BREAKING)
    }
    exitProcess(EXIT_OK)
}
