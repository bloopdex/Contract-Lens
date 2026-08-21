// Pins the contractlens-signal v1 builder (ADR-008): shape, counts,
// deterministic operation grouping, and the privacy boundary — the
// payload carries metadata only, never property names, locations, or
// classification prose.

package dev.bloopdex.contractlens.core.signal

import dev.bloopdex.contractlens.core.classify.ClassificationReport
import dev.bloopdex.contractlens.core.classify.ClassifiedChange
import dev.bloopdex.contractlens.core.classify.SemverLevel
import dev.bloopdex.contractlens.core.classify.Verdict
import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ChangeValue
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.impact.ConsumerImpact
import dev.bloopdex.contractlens.core.impact.ImpactReport
import dev.bloopdex.contractlens.core.impact.ImpactedChange
import dev.bloopdex.contractlens.core.impact.ImpactedOperation
import dev.bloopdex.contractlens.core.registry.Consumer
import dev.bloopdex.contractlens.core.registry.ConsumerKind
import dev.bloopdex.contractlens.core.registry.parseSelector
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun change(
    kind: ChangeKind,
    location: String,
    from: String? = null,
    to: String? = null,
): ContractChange =
    ContractChange(
        kind = kind,
        target = ChangeTarget.SCHEMA,
        location = location,
        sourceLocation = null,
        from = from?.let { ChangeValue(it) },
        to = to?.let { ChangeValue(it) },
        explanation = "test explanation for $location",
    )

private fun classified(
    change: ContractChange,
    verdict: Verdict,
    semver: SemverLevel?,
): ClassifiedChange = ClassifiedChange(change = change, verdict = verdict, semver = semver, reason = "reason for ${change.kind}")

private val breakingUsers =
    change(ChangeKind.PROPERTY_REMOVED, "GET /users → response 200 → schema → properties.secretEmail", from = "string")
private val reviewUsers =
    change(ChangeKind.ENUM_CHANGED, "GET /users/{id} → response 200 → schema → properties.role", from = "admin", to = "editor")
private val additiveUsers =
    change(ChangeKind.PROPERTY_ADDED, "POST /users/{id} → request body → schema → properties.nickname", to = "string")

private fun report(): ClassificationReport {
    val classifiedChanges =
        listOf(
            classified(breakingUsers, Verdict.BREAKING, SemverLevel.MAJOR),
            classified(reviewUsers, Verdict.REVIEW, null),
            classified(additiveUsers, Verdict.NON_BREAKING, SemverLevel.MINOR),
        )
    return ClassificationReport(
        changes = classifiedChanges,
        summary =
            dev.bloopdex.contractlens.core.classify.ClassificationSummaryBuilder
                .of(classifiedChanges),
    )
}

private fun build(
    impact: ImpactReport? = null,
    analyzedAt: String = "2026-08-19T00:00:00Z",
) = SignalBuilder.build(
    contract = "users",
    oldSha = "a".repeat(40),
    newSha = "b".repeat(40),
    classification = report(),
    impact = impact,
    analysisDurationMs = 12.5,
    analyzedAt = analyzedAt,
    producerVersion = "1.0.0",
)

class SignalBuilderTest :
    FunSpec({

        test("counts and semver come from the classification report") {
            val signal = build()
            signal.format shouldBe "contractlens-signal"
            signal.version shouldBe 1
            signal.contract shouldBe "users"
            signal.changes.total shouldBe 3
            signal.changes.breaking shouldBe 1
            signal.changes.nonBreaking shouldBe 1
            signal.changes.review shouldBe 1
            signal.semver shouldBe SemverLevel.MAJOR
            signal.consumers shouldBe null
            signal.metrics.analysisDurationMs shouldBe 12.5
        }

        test("operations are canonical identities, sorted, with per-verdict counts") {
            val signal = build()
            signal.operations.map { it.identity } shouldBe listOf("GET /users", "GET /users/{}", "POST /users/{}")
            val users = signal.operations.first { it.identity == "GET /users" }
            users.totalChanges shouldBe 1
            users.breakingChanges shouldBe 1
            val usersId = signal.operations.first { it.identity == "GET /users/{}" }
            usersId.reviewChanges shouldBe 1
            val post = signal.operations.first { it.identity == "POST /users/{}" }
            post.nonBreakingChanges shouldBe 1
        }

        test("consumers come from the impact report with breaking counts") {
            val impact =
                ImpactReport(
                    contract = "users",
                    changes = listOf(breakingUsers, reviewUsers),
                    impacts =
                        listOf(
                            ConsumerImpact(
                                consumer =
                                    Consumer(
                                        id = "example-frontend",
                                        kind = ConsumerKind.FRONTEND,
                                        contract = "users",
                                        selectors = listOf(parseSelector("example-frontend", "*")),
                                    ),
                                changes =
                                    listOf(
                                        ImpactedChange(
                                            operation = ImpactedOperation(method = "get", path = "/users", pathIdentity = "/users"),
                                            change = breakingUsers,
                                            reason = "consumer declares this operation",
                                        ),
                                        ImpactedChange(
                                            operation = ImpactedOperation(method = "get", path = "/users/{id}", pathIdentity = "/users/{}"),
                                            change = reviewUsers,
                                            reason = "consumer declares this operation",
                                        ),
                                    ),
                            ),
                        ),
                )
            val signal = build(impact = impact)
            val consumer = signal.consumers!!.single()
            consumer.id shouldBe "example-frontend"
            consumer.kind shouldBe "frontend"
            consumer.affectedChanges shouldBe 2
            consumer.breakingChanges shouldBe 1
        }

        test("the payload never carries property names, locations, or prose") {
            val impact =
                ImpactReport(
                    contract = "users",
                    changes = listOf(breakingUsers),
                    impacts =
                        listOf(
                            ConsumerImpact(
                                consumer =
                                    Consumer(
                                        id = "example-frontend",
                                        kind = ConsumerKind.FRONTEND,
                                        contract = "users",
                                        selectors = listOf(parseSelector("example-frontend", "*")),
                                    ),
                                changes =
                                    listOf(
                                        ImpactedChange(
                                            operation = ImpactedOperation(method = "get", path = "/users", pathIdentity = "/users"),
                                            change = breakingUsers,
                                            reason = "consumer declares this operation",
                                        ),
                                    ),
                            ),
                        ),
                )
            val signal = build(impact = impact)
            val json = CanonicalJson.encodeToString(ContractLensSignal.serializer(), signal)
            json shouldContain "\"GET /users\""
            for (forbidden in listOf("secretEmail", "role", "nickname", "properties", "reason for", "declares this operation")) {
                json shouldNotContain forbidden
            }
        }

        test("identical analysis inputs produce identical bytes except analyzedAt") {
            val first = build(analyzedAt = "2026-08-19T00:00:00Z")
            val second = build(analyzedAt = "2026-08-19T00:00:01Z")
            first.copy(analyzedAt = "") shouldBe second.copy(analyzedAt = "")
            first.analyzedAt shouldNotBe second.analyzedAt
        }
    })
