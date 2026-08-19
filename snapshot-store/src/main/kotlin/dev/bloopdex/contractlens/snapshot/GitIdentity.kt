// Commit-identity resolution for snapshot capture (ADR-003):
// an explicit --sha always wins; otherwise git must provide HEAD.
// When neither is available the capture fails loudly — an identity is
// never invented.

package dev.bloopdex.contractlens.snapshot

import dev.bloopdex.contractlens.core.error.ContractError
import java.nio.file.Path

object GitIdentity {

    fun require(override: String?, cwd: Path): String =
        override ?: GitRev.headSha(cwd)
        ?: throw ContractError.GitIdentityUnavailable(
            "the current directory is not inside a git repository with a HEAD commit"
        )
}
