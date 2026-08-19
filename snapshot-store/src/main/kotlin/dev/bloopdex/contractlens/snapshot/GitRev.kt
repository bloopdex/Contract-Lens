// Git commit identity for snapshots (ADR-003).
//
// The commit SHA is discovered by asking git (`git rev-parse HEAD`),
// never invented: when the working directory is not a git repository or
// has no commits, the caller must supply an identity explicitly.

package dev.bloopdex.contractlens.snapshot

import java.nio.file.Path

object GitRev {

    /** Full HEAD SHA of the repository containing [cwd], or null when unavailable. */
    fun headSha(cwd: Path): String? {
        val process = try {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return null
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = try {
            process.waitFor()
        } catch (e: Exception) {
            return null
        }
        if (exit != 0) return null
        if (!output.matches(Regex("[0-9a-f]{40,64}"))) return null
        return output
    }
}
