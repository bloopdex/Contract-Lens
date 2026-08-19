// Classification model (Phase 0, ADR-001; implemented Phase 4).
//
// The classifier consumes the structural change set and attaches the
// documented three-verdict model (breaking / non-breaking / review)
// plus a semver label (major / minor / patch). It is a separate layer:
// the DiffEngine still answers "what changed", the classifier answers
// "what does the Phase 0 ruleset say about it", and the verdict stays
// null in the diff engine's own output.
//
// Semver labels derive from verdicts, never independently:
//   breaking -> major; non-breaking + additive kind -> minor;
//   non-breaking otherwise -> patch; review -> no label (semver cannot
//   express human judgment).

package dev.bloopdex.contractlens.core.classify

import dev.bloopdex.contractlens.core.diff.ContractChange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Verdict {
    @SerialName("breaking")
    BREAKING,

    @SerialName("non-breaking")
    NON_BREAKING,

    @SerialName("review")
    REVIEW,
}

@Serializable
enum class SemverLevel {
    @SerialName("major")
    MAJOR,

    @SerialName("minor")
    MINOR,

    @SerialName("patch")
    PATCH,
}

@Serializable
data class ClassifiedChange(
    /** The structural change, with its documented `verdict` slot filled (a copy — inputs are never mutated). */
    val change: ContractChange,
    val verdict: Verdict,
    /** null exactly when the verdict is review: semver cannot express human judgment. */
    val semver: SemverLevel?,
    /** Why the rule produced this verdict (explainable classification). */
    val reason: String,
)

@Serializable
data class ClassificationSummary(
    val total: Int,
    val breaking: Int,
    val nonBreaking: Int,
    val review: Int,
    /** The highest semver level across all changes; null when no change carries one. */
    val semver: SemverLevel?,
)

@Serializable
data class ClassificationReport(
    /** Same order as the input change set (changeOrder-sorted). */
    val changes: List<ClassifiedChange>,
    val summary: ClassificationSummary,
)

object ClassificationSummaryBuilder {
    fun of(changes: List<ClassifiedChange>): ClassificationSummary {
        val levels = changes.mapNotNull { it.semver }
        return ClassificationSummary(
            total = changes.size,
            breaking = changes.count { it.verdict == Verdict.BREAKING },
            nonBreaking = changes.count { it.verdict == Verdict.NON_BREAKING },
            review = changes.count { it.verdict == Verdict.REVIEW },
            semver = levels.maxByOrNull { it.rank },
        )
    }
}

internal val SemverLevel.rank: Int
    get() =
        when (this) {
            SemverLevel.MAJOR -> 3
            SemverLevel.MINOR -> 2
            SemverLevel.PATCH -> 1
        }
