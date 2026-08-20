// Benchmark regression policy (docs/benchmarks.md):
//
// Comparisons are computed against the committed baseline
// (docs/benchmarks/baseline.json). Threshold rationale:
//   - the scenarios measure 1-50 ms operations; shared CI runners add
//     scheduling/GC noise that routinely doubles a run, so a bare ratio
//     would fail on noise ("any 1 ms difference = failure" is exactly
//     what the benchmark policy forbids).
//   - a regression is only a FAIL when it is BOTH at least 3x the
//     baseline median AND above 1000 ms — i.e. noise cannot explain it
//     and a user-visible slowdown exists; plus an absolute sanity bound
//     of 2000 ms on any scenario.
//   - 2x..3x is a WARN: printed prominently, investigated, but not a
//     failing build. Baselines are ONLY updated by a conscious
//     maintainer run (`:benchmark:bench`) — never automatically, never
//     to make a comparison pass.
//   - cross-environment comparisons (e.g. Linux CI numbers against the
//     Windows workstation baseline) are informational by construction:
//     different OSes cannot be compared as equivalent measurements.
//     The FAIL gate therefore only applies when the OS family matches
//     the committed baseline's.

package dev.bloopdex.contractlens.benchmark

enum class ComparisonVerdict { OK, WARN, FAIL, INFORMATIONAL }

data class ComparisonRow(
    val scenario: String,
    val baselineMedianMs: Double?,
    val currentMedianMs: Double,
    val ratio: Double?,
    val verdict: ComparisonVerdict,
)

const val FAIL_RATIO = 3.0
const val FAIL_ABSOLUTE_MS = 1000.0
const val FAIL_SANITY_MS = 2000.0
const val WARN_RATIO = 2.0

fun osFamily(environment: BenchmarkEnvironment): String = environment.os.substringBefore(' ').lowercase()

/** Pure comparison: never mutates, never rewrites the baseline. */
fun compareAgainstBaseline(
    current: BenchmarkBaseline,
    committed: BenchmarkBaseline,
): List<ComparisonRow> {
    val baselineByScenario = committed.results.associateBy { it.scenario }
    val sameEnvironment = osFamily(current.environment) == osFamily(committed.environment)
    return current.results.map { result ->
        val baseline = baselineByScenario[result.scenario]
        val ratio = if (baseline != null && baseline.medianMs > 0.0) result.medianMs / baseline.medianMs else null
        val verdict =
            when {
                !sameEnvironment -> ComparisonVerdict.INFORMATIONAL
                baseline == null -> ComparisonVerdict.WARN // new scenario: needs a committed baseline
                result.medianMs > FAIL_SANITY_MS -> ComparisonVerdict.FAIL
                ratio != null && ratio > FAIL_RATIO && result.medianMs > FAIL_ABSOLUTE_MS -> ComparisonVerdict.FAIL
                ratio != null && ratio > WARN_RATIO -> ComparisonVerdict.WARN
                else -> ComparisonVerdict.OK
            }
        ComparisonRow(
            scenario = result.scenario,
            baselineMedianMs = baseline?.medianMs,
            currentMedianMs = result.medianMs,
            ratio = ratio,
            verdict = verdict,
        )
    }
}

fun List<ComparisonRow>.hasFailures(): Boolean = any { it.verdict == ComparisonVerdict.FAIL }
