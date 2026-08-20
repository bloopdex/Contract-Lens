// Pins the Phase 6 benchmark regression policy (BenchmarkCheck.kt):
// noise-sized changes never fail, catastrophic regressions do, and
// cross-environment comparisons are informational by construction.

package dev.bloopdex.contractlens.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun env(os: String): BenchmarkEnvironment =
    BenchmarkEnvironment(os = os, javaVersion = "17", availableProcessors = 4, maxMemoryMb = 4096)

private fun result(
    medianMs: Double,
    scenario: String = "openapi-parse-5k",
): BenchmarkResult =
    BenchmarkResult(scenario = scenario, inputSize = "5k", warmupRuns = 3, timedRuns = 7, medianMs = medianMs, minMs = medianMs)

private val committedWindows = BenchmarkBaseline(environment = env("Windows 11 10.0"), results = listOf(result(49.4)))

class BenchmarkCheckTest {
    @Test
    fun `noise-sized changes on the same OS family are OK`() {
        val current = BenchmarkBaseline(environment = env("Windows Server 2025"), results = listOf(result(80.0)))
        val rows = compareAgainstBaseline(current, committedWindows)
        assertEquals(ComparisonVerdict.OK, rows.single().verdict)
        assertFalse(rows.hasFailures())
    }

    @Test
    fun `a 2x-to-3x slowdown on the same OS family is a WARN, not a failure`() {
        val current = BenchmarkBaseline(environment = env("Windows Server 2025"), results = listOf(result(120.0)))
        val rows = compareAgainstBaseline(current, committedWindows)
        assertEquals(ComparisonVerdict.WARN, rows.single().verdict)
        assertFalse(rows.hasFailures())
    }

    @Test
    fun `a catastrophic slowdown that is both 3x and above the absolute floor FAILS`() {
        val current = BenchmarkBaseline(environment = env("Windows Server 2025"), results = listOf(result(1800.0)))
        val rows = compareAgainstBaseline(current, committedWindows)
        assertEquals(ComparisonVerdict.FAIL, rows.single().verdict)
        assertTrue(rows.hasFailures())
    }

    @Test
    fun `a 3x slowdown of a sub-millisecond-scale scenario does not fail on its own`() {
        // 49.4 -> 140 is 2.8x... use a small baseline: 10 -> 35 is 3.5x but
        // far under the absolute floor — noise territory, WARN only.
        val smallBaseline = BenchmarkBaseline(environment = env("Windows 11 10.0"), results = listOf(result(10.0)))
        val current = BenchmarkBaseline(environment = env("Windows Server 2025"), results = listOf(result(35.0)))
        val rows = compareAgainstBaseline(current, smallBaseline)
        assertEquals(ComparisonVerdict.WARN, rows.single().verdict)
        assertFalse(rows.hasFailures())
    }

    @Test
    fun `any scenario above the absolute sanity bound FAILS regardless of ratio`() {
        val current = BenchmarkBaseline(environment = env("Windows Server 2025"), results = listOf(result(2100.0)))
        val rows = compareAgainstBaseline(current, committedWindows)
        assertEquals(ComparisonVerdict.FAIL, rows.single().verdict)
    }

    @Test
    fun `cross-environment comparisons are informational and never fail`() {
        val current = BenchmarkBaseline(environment = env("Linux 6.8"), results = listOf(result(5000.0)))
        val rows = compareAgainstBaseline(current, committedWindows)
        assertEquals(ComparisonVerdict.INFORMATIONAL, rows.single().verdict)
        assertFalse(rows.hasFailures())
    }

    @Test
    fun `a scenario missing from the committed baseline is a WARN`() {
        val current = BenchmarkBaseline(environment = env("Windows Server 2025"), results = listOf(result(5.0, "new-scenario")))
        val rows = compareAgainstBaseline(current, committedWindows)
        assertEquals(ComparisonVerdict.WARN, rows.single().verdict)
    }
}
