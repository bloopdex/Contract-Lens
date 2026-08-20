// The classification fixture corpus: the 14-case
// catalog (plus the derived kinds) as end-to-end pairs — real parser,
// real engine, real classifier, expected verdicts and semver labels.
// Expected files are reviewed like code: they change only when a rule
// deliberately changes. On mismatch the test writes actual.json next to
// the case for adjudication.

package dev.bloopdex.contractlens.cli

import dev.bloopdex.contractlens.core.classify.Classifier
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import dev.bloopdex.contractlens.openapi.OpenApiParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ExpectedClassification(
    val kind: String,
    val location: String,
    val verdict: String,
    val semver: String?,
)

@Serializable
data class ExpectedClassificationReport(
    val changeCount: Int,
    val breaking: Int,
    val nonBreaking: Int,
    val review: Int,
    val semver: String?,
    val changes: List<ExpectedClassification>,
)

private val classificationPrettyJson = Json(from = CanonicalJson) { prettyPrint = true }

class ClassificationFixtureTest :
    FunSpec({

        val fixtureRoot =
            Path.of(
                requireNotNull(javaClass.getResource("/fixtures/classify")).toURI(),
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
                    val report = Classifier.classify(DiffEngine.diff(old, new), old, new)
                    val actual =
                        ExpectedClassificationReport(
                            changeCount = report.changes.size,
                            breaking = report.summary.breaking,
                            nonBreaking = report.summary.nonBreaking,
                            review = report.summary.review,
                            semver =
                                report.summary.semver
                                    ?.name
                                    ?.lowercase(),
                            changes =
                                report.changes.map { entry ->
                                    ExpectedClassification(
                                        kind = entry.change.kind.name,
                                        location = entry.change.location,
                                        verdict =
                                            entry.verdict.name
                                                .lowercase()
                                                .replace('_', '-'),
                                        semver = entry.semver?.name?.lowercase(),
                                    )
                                },
                        )
                    val expected =
                        if (Files.exists(expectedPath)) {
                            CanonicalJson.decodeFromString(ExpectedClassificationReport.serializer(), Files.readString(expectedPath).trim())
                        } else {
                            null
                        }
                    if (expected == null || actual != expected) {
                        Files.writeString(
                            caseDir.toPath().resolve("actual.json"),
                            classificationPrettyJson.encodeToString(ExpectedClassificationReport.serializer(), actual) + "\n",
                        )
                    }
                    actual shouldBe expected
                }
            }
    })
