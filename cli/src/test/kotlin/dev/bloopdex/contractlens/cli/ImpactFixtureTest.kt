// The consumer-impact end-to-end fixture corpus: every mapping rule has an
// old/new OpenAPI pair plus a registry and an expected impact report.
// Specs go through the REAL parser, changes come from the REAL diff
// engine, the registry goes through the REAL registry adapter, and the
// mapping runs in the REAL mapper. Expected files are reviewed like
// code: they change only when a rule deliberately changes. On mismatch
// the test writes actual.json next to the case for adjudication.

package dev.bloopdex.contractlens.cli

import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.impact.ConsumerMapper
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.openapi.OpenApiParser
import dev.bloopdex.contractlens.registry.RegistryParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ExpectedImpactedChange(
    val method: String,
    val path: String,
    val kind: String,
    val location: String,
    val reason: String,
)

@Serializable
data class ExpectedImpact(
    val consumer: String,
    val changes: List<ExpectedImpactedChange>,
)

@Serializable
data class ExpectedImpactReport(
    val contract: String,
    val changeCount: Int,
    val impactedConsumerCount: Int,
    val mappedChanges: Int,
    val impacts: List<ExpectedImpact>,
)

private val prettyJson = Json(from = CanonicalJson) { prettyPrint = true }

class ImpactFixtureTest :
    FunSpec({

        val fixtureRoot =
            Path.of(
                requireNotNull(javaClass.getResource("/fixtures/impact")).toURI(),
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
                val registryPath = caseDir.toPath().resolve("registry.yaml")
                val expectedPath = caseDir.toPath().resolve("expected.json")

                test("fixture: ${caseDir.name}") {
                    val old = parser.parse(oldPath, "fixture")
                    val new = parser.parse(newPath, "fixture")
                    val changes = DiffEngine.diff(old, new)
                    val registry = RegistryParser.parse(Files.readString(registryPath), "fixture-registry.yaml")
                    val report = ConsumerMapper.map(changes, registry, new.name)
                    val actual =
                        ExpectedImpactReport(
                            contract = report.contract,
                            changeCount = report.changes.size,
                            impactedConsumerCount = report.impacts.size,
                            mappedChanges = ConsumerMapper.mappedChangeCount(report.impacts),
                            impacts =
                                report.impacts.map { impact ->
                                    ExpectedImpact(
                                        consumer = impact.consumer.id,
                                        changes =
                                            impact.changes.map { entry ->
                                                ExpectedImpactedChange(
                                                    method = entry.operation.method,
                                                    path = entry.operation.path,
                                                    kind = entry.change.kind.name,
                                                    location = entry.change.location,
                                                    reason = entry.reason,
                                                )
                                            },
                                    )
                                },
                        )
                    val expected =
                        if (Files.exists(expectedPath)) {
                            CanonicalJson.decodeFromString(ExpectedImpactReport.serializer(), Files.readString(expectedPath).trim())
                        } else {
                            null
                        }
                    if (expected == null || actual != expected) {
                        Files.writeString(
                            caseDir.toPath().resolve("actual.json"),
                            prettyJson.encodeToString(ExpectedImpactReport.serializer(), actual) + "\n",
                        )
                    }
                    actual shouldBe expected
                }
            }
    })
