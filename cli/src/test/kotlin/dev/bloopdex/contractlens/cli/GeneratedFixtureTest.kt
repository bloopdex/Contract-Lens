// The Phase 4 generated-client fixture corpus: OpenAPI pairs projected
// through the TS AND Kotlin conventions and diffed with the shared
// engine. The expected files pin the generated-client-shaped change
// locations. Both styles share naming conventions at this depth (ADR-006),
// so each case has one expected file and the harness asserts that ts and
// kotlin projections agree — the style label itself is pinned by
// GeneratedDiffCommandTest. On mismatch the test writes actual.json next
// to the case for adjudication.

package dev.bloopdex.contractlens.cli

import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.generated.GeneratedClientProjection
import dev.bloopdex.contractlens.generated.GeneratorStyle
import dev.bloopdex.contractlens.openapi.OpenApiParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ExpectedGeneratedChange(
    val kind: String,
    val location: String,
)

private val generatedPrettyJson = Json(from = CanonicalJson) { prettyPrint = true }

class GeneratedFixtureTest :
    FunSpec({

        val fixtureRoot =
            Path.of(
                requireNotNull(javaClass.getResource("/fixtures/generated")).toURI(),
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

                    fun changesFor(style: GeneratorStyle) =
                        DiffEngine
                            .diff(
                                GeneratedClientProjection.project(old, style),
                                GeneratedClientProjection.project(new, style),
                            ).map { ExpectedGeneratedChange(kind = it.kind.name, location = it.location) }
                    val tsChanges = changesFor(GeneratorStyle.TYPESCRIPT)
                    val kotlinChanges = changesFor(GeneratorStyle.KOTLIN)
                    kotlinChanges shouldBe tsChanges // style-agnostic at this depth (ADR-006)
                    val expected =
                        if (Files.exists(expectedPath)) {
                            CanonicalJson.decodeFromString(
                                kotlinx.serialization.builtins.ListSerializer(ExpectedGeneratedChange.serializer()),
                                Files.readString(expectedPath).trim(),
                            )
                        } else {
                            null
                        }
                    if (expected == null || tsChanges != expected) {
                        Files.writeString(
                            caseDir.toPath().resolve("actual.json"),
                            generatedPrettyJson.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(ExpectedGeneratedChange.serializer()),
                                tsChanges,
                            ) + "\n",
                        )
                    }
                    tsChanges shouldBe expected
                }
            }
    })
