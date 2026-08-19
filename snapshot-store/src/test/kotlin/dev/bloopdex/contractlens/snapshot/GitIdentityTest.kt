// Commit-identity resolution: an explicit identity always wins; without
// one, a non-git directory fails loudly (no invented identities).

package dev.bloopdex.contractlens.snapshot

import dev.bloopdex.contractlens.core.error.ContractError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class GitIdentityTest :
    FunSpec({

        test("an explicit override always wins, even outside a git repo") {
            GitIdentity.require("abc".repeat(14), Files.createTempDirectory("nogit")) shouldBe "abc".repeat(14)
        }

        test("without an override, a non-git directory fails loudly") {
            val e =
                shouldThrow<ContractError.GitIdentityUnavailable> {
                    GitIdentity.require(null, Files.createTempDirectory("nogit"))
                }
            e.code shouldBe "GIT_IDENTITY_UNAVAILABLE"
        }

        test("without an override, a git repository provides its HEAD sha") {
            // The module's own working directory is inside the contractlens
            // repository during tests: HEAD must resolve to a 40+ hex sha.
            val sha =
                GitIdentity.require(
                    null,
                    java.nio.file.Path
                        .of("")
                        .toAbsolutePath(),
                )
            sha.matches(Regex("[0-9a-f]{40,64}")) shouldBe true
        }
    })
