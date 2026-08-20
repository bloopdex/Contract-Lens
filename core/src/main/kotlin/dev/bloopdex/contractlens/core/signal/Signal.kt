// contractlens-signal v1 — the proposed DeployScore feed payload
// (ADR-008, Phase 6). The full contract lives in docs/deployscore-feed.md.
//
// Design constraints, enforced here BY CONSTRUCTION:
//   - metadata only: counts, verdicts, semver, operation identities
//     (canonical "METHOD /pathIdentity" form), and registry-declared
//     consumer ids. NO property names, no locations, no values, no
//     descriptions, no reasons — the privacy boundary is tighter than
//     the diff report's.
//   - deterministic: identical analysis inputs produce identical bytes
//     (analyzedAt is the single variable-metadata field, like the
//     snapshot format's capturedAt).
//   - derived, never duplicated: every field comes from the existing
//     classification report and (optionally) the impact report — there
//     is no second telemetry model.
//   - offline-safe: the payload is data; emission (CLI) is file/stdout
//     only. No network integration exists, so an unavailable DeployScore
//     can never affect local analysis.

package dev.bloopdex.contractlens.core.signal

import dev.bloopdex.contractlens.core.classify.ClassificationReport
import dev.bloopdex.contractlens.core.classify.SemverLevel
import dev.bloopdex.contractlens.core.classify.Verdict
import dev.bloopdex.contractlens.core.impact.ImpactReport
import dev.bloopdex.contractlens.core.impact.affectedOperations
import kotlinx.serialization.Serializable

const val SIGNAL_FORMAT_VERSION = 1

@Serializable
data class SignalIdentity(
    val contract: String,
    val sha: String,
)

@Serializable
data class SignalChangeCounts(
    val total: Int,
    val breaking: Int,
    val nonBreaking: Int,
    val review: Int,
)

@Serializable
data class SignalOperation(
    /** Canonical operation identity: uppercase method + path identity. */
    val identity: String,
    val totalChanges: Int,
    val breakingChanges: Int,
    val nonBreakingChanges: Int,
    val reviewChanges: Int,
)

@Serializable
data class SignalConsumer(
    val id: String,
    val kind: String,
    val affectedChanges: Int,
    val breakingChanges: Int,
)

@Serializable
data class SignalMetrics(
    val analysisDurationMs: Double,
)

@Serializable
data class ContractLensSignal(
    val format: String = "contractlens-signal",
    val version: Int = SIGNAL_FORMAT_VERSION,
    val producer: String = "contractlens",
    val producerVersion: String,
    val contract: String,
    /** Variable metadata (ISO-8601) — the only non-deterministic field, like the snapshot's capturedAt. */
    val analyzedAt: String,
    val old: SignalIdentity,
    val new: SignalIdentity,
    val changes: SignalChangeCounts,
    val semver: SemverLevel?,
    /** Sorted by canonical identity; one entry per changed operation. */
    val operations: List<SignalOperation>,
    /** Present only when a registry was analyzed; sorted by consumer id. */
    val consumers: List<SignalConsumer>? = null,
    val metrics: SignalMetrics,
)

object SignalBuilder {
    /**
     * Builds the v1 payload from the two reports the CLI already
     * produces. Pure and deterministic: the same inputs produce the
     * same payload (except [analyzedAt], which the caller supplies).
     */
    fun build(
        contract: String,
        oldSha: String,
        newSha: String,
        classification: ClassificationReport,
        impact: ImpactReport?,
        analysisDurationMs: Double,
        analyzedAt: String,
        producerVersion: String,
    ): ContractLensSignal {
        val verdictByChange = classification.changes.associateBy { it.change.copy(verdict = null) }

        // Operations: group the classified changes by the canonical
        // operation identity; changes outside the location grammar
        // (defensive, per the impact model) carry no operation and are
        // only counted in the totals — never guessed.
        val grouped = LinkedHashMap<String, MutableList<dev.bloopdex.contractlens.core.classify.ClassifiedChange>>()
        for (classified in classification.changes) {
            for (operation in affectedOperations(classified.change)) {
                val identity = "${operation.method.uppercase()} ${operation.pathIdentity}"
                grouped.getOrPut(identity) { mutableListOf() } += classified
            }
        }
        val operations =
            grouped
                .toSortedMap()
                .map { (identity, entries) ->
                    SignalOperation(
                        identity = identity,
                        totalChanges = entries.size,
                        breakingChanges = entries.count { it.verdict == Verdict.BREAKING },
                        nonBreakingChanges = entries.count { it.verdict == Verdict.NON_BREAKING },
                        reviewChanges = entries.count { it.verdict == Verdict.REVIEW },
                    )
                }

        val consumers =
            impact
                ?.impacts
                ?.map { consumerImpact ->
                    val breaking = consumerImpact.changes.count { verdictByChange[it.change]?.verdict == Verdict.BREAKING }
                    SignalConsumer(
                        id = consumerImpact.consumer.id,
                        kind =
                            consumerImpact.consumer.kind.name
                                .lowercase(),
                        affectedChanges = consumerImpact.changes.size,
                        breakingChanges = breaking,
                    )
                }?.sortedBy { it.id }

        return ContractLensSignal(
            producerVersion = producerVersion,
            contract = contract,
            analyzedAt = analyzedAt,
            old = SignalIdentity(contract = contract, sha = oldSha),
            new = SignalIdentity(contract = contract, sha = newSha),
            changes =
                SignalChangeCounts(
                    total = classification.summary.total,
                    breaking = classification.summary.breaking,
                    nonBreaking = classification.summary.nonBreaking,
                    review = classification.summary.review,
                ),
            semver = classification.summary.semver,
            operations = operations,
            consumers = consumers,
            metrics = SignalMetrics(analysisDurationMs = analysisDurationMs),
        )
    }
}
