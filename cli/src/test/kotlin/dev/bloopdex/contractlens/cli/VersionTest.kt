// Pins the version contract (ADR-007): `--version` reports the
// version from the single authoritative source (`version` in the root
// build.gradle.kts, generated into CONTRACTLENS_VERSION). The literal
// here duplicates the build file on purpose — the release script
// re-verifies that the two stay in sync, and this test makes any drift
// fail the build.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class VersionTest :
    FunSpec({
        test("--version reports the authoritative version from the generated constant") {
            versionLine(arrayOf("--version")) shouldBe "contractlens version $CONTRACTLENS_VERSION"
            CONTRACTLENS_VERSION shouldBe "1.0.1"
        }

        test("versionLine ignores every other argument shape") {
            versionLine(arrayOf("--help")) shouldBe null
            versionLine(arrayOf("--version", "extra")) shouldBe null
            versionLine(emptyArray()) shouldBe null
        }

        test("the root help mentions the version flag") {
            val result = ContractLensCommand().subcommands(SnapshotCommand()).test(arrayOf("--help"))
            result.statusCode shouldBe 0
            result.output shouldContain "Version: --version"
        }
    })
