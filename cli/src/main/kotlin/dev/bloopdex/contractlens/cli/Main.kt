// contractlens CLI entry point.
//
// Exit code contract (Architecture page, Phase 0):
//   0 - success
//   1 - RESERVED: breaking changes detected (arrives with the Phase 2
//       diff engine; nothing produces it in Phase 1)
//   2 - operational/error condition (bad usage, bad input, IO errors,
//       corrupt snapshots)
//
// Clikt reports usage errors as status 1; they are remapped to 2 so the
// reserved code stays reserved for breaking changes.

package dev.bloopdex.contractlens.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import dev.bloopdex.contractlens.core.error.ContractError
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

const val EXIT_OK = 0
const val EXIT_BREAKING = 1
const val EXIT_ERROR = 2

/** Base command: --verbose turns on debug-level structured logging (stderr). */
abstract class BaseCommand(name: String, help: String) : CliktCommand(name = name, help = help) {
    private val verbose by option("-v", "--verbose", help = "Enable debug-level structured logs on stderr").flag()

    override fun run() {
        if (verbose) {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.DEBUG
        }
        runCommand()
    }

    abstract fun runCommand()
}

class Main : CliktCommand(name = "contractlens") {
    override fun run() = Unit
}

fun main(args: Array<String>) {
    val command = Main().subcommands(SnapshotCommand(), VerifyCommand(), ListCommand())
    val status = try {
        command.main(args)
    } catch (e: ContractError) {
        System.err.println("error [${e.code}]: ${e.message}")
        EXIT_ERROR
    } catch (e: Exception) {
        System.err.println("error [INTERNAL]: ${e.message ?: e.javaClass.simpleName}")
        EXIT_ERROR
    }
    // Clikt usage errors arrive as 1; the contract reserves 1 for
    // breaking changes (Phase 2), so usage errors are operational.
    val mapped = if (status == EXIT_BREAKING) EXIT_ERROR else status
    if (mapped != EXIT_OK) exitProcess(mapped)
}
