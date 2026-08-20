// The fixture corpus: every structural rule has an old/new OpenAPI pair
// plus an expected change projection (kind, target, location, from, to).
// The pair is parsed through the REAL parser adapter and
// diffed with the REAL engine — an end-to-end regression suite. The
// expected files are reviewed like code: they change only when a rule
// deliberately changes.

package dev.bloopdex.contractlens.cli

import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.openapi.OpenApiParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ExpectedChange(
    val kind: String,
    val target: String,
    val location: String,
    val from: String? = null,
    val to: String? = null,
)

class DiffFixtureTest :
    FunSpec({

        val fixtureRoot =
            Path.of(
                requireNotNull(javaClass.getResource("/fixtures/diff")).toURI(),
            )
        val parser = OpenApiParser()

        fixtureRoot
            .toFile()
            .listFiles()!!
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .forEach { caseDir ->
                val oldPath = caseDir.toPath().resolve("old.yaml")
                val newPath = caseDir.toPath().resolve("new.yaml")
                val expectedPath = caseDir.toPath().resolve("expected.json")

                test("fixture: ${caseDir.name}") {
                    val old = parser.parse(oldPath, "fixture")
                    val new = parser.parse(newPath, "fixture")
                    val actual =
                        DiffEngine.diff(old, new).map {
                            ExpectedChange(
                                kind = it.kind.name,
                                target = it.target.name,
                                location = it.location,
                                from = it.from?.summary,
                                to = it.to?.summary,
                            )
                        }
                    val expected =
                        CanonicalJson.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(ExpectedChange.serializer()),
                            Files.readString(expectedPath).trim(),
                        )
                    actual shouldBe expected
                }
            }
    })
