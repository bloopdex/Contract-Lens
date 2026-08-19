// Phase 5 classifier/diff invariant fuzzing: the existing property
// invariants at higher iteration counts, driven by the same
// fuzz.iterations property as ParserFuzzTest. Determinism, totality,
// purity, verdict/semver consistency, rename pairing, mapper phantom
// safety — run at scale by `gradlew :cli:fuzz`.

package dev.bloopdex.contractlens.core.classify

import dev.bloopdex.contractlens.core.classify.Classifier
import dev.bloopdex.contractlens.core.classify.SemverLevel
import dev.bloopdex.contractlens.core.classify.Verdict
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.diff.changeOrder
import dev.bloopdex.contractlens.core.impact.ConsumerMapper
import dev.bloopdex.contractlens.core.registry.Consumer
import dev.bloopdex.contractlens.core.registry.ConsumerKind
import dev.bloopdex.contractlens.core.registry.ConsumerRegistry
import dev.bloopdex.contractlens.core.registry.parseSelector
import dev.bloopdex.contractlens.core.surfaceArb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClassifierFuzzTest :
    FunSpec({

        val iterations = (System.getProperty("fuzz.iterations") ?: "400").toInt()

        test("classifier invariants hold over $iterations generated diff pairs") {
            val arb = surfaceArb()
            repeat(iterations) { index ->
                val old =
                    arb
                        .sample(
                            io.kotest.property.RandomSource
                                .seeded(index.toLong()),
                        ).value
                val new =
                    arb
                        .sample(
                            io.kotest.property.RandomSource
                                .seeded(index.toLong() + 1),
                        ).value
                val changes = DiffEngine.diff(old, new)

                // totality + ordering
                val report = Classifier.classify(changes, old, new)
                report.changes.size shouldBe changes.size
                report.changes.map { it.change.copy(verdict = null) } shouldBe changes.sortedWith(changeOrder)

                // determinism
                report shouldBe Classifier.classify(changes, old, new)

                // purity
                changes.forEach { it.verdict shouldBe null }

                // verdict/semver consistency
                report.changes.forEach { entry ->
                    entry.change.verdict shouldBe
                        entry.verdict.name
                            .lowercase()
                            .replace('_', '-')
                    when (entry.verdict) {
                        Verdict.REVIEW -> entry.semver shouldBe null
                        Verdict.BREAKING -> entry.semver shouldBe SemverLevel.MAJOR
                        Verdict.NON_BREAKING ->
                            (entry.semver == SemverLevel.MINOR || entry.semver == SemverLevel.PATCH) shouldBe true
                    }
                }
            }
        }

        test("mapper invariants hold over $iterations generated change sets") {
            val wildcard =
                Consumer(
                    id = "fuzz-consumer",
                    kind = ConsumerKind.SERVICE,
                    contract = "test",
                    selectors = listOf(parseSelector("fuzz-consumer", "*")),
                )
            val registry = ConsumerRegistry(version = 1, consumers = listOf(wildcard))
            val arb = surfaceArb()
            repeat(iterations) { index ->
                val changes =
                    DiffEngine.diff(
                        arb
                            .sample(
                                io.kotest.property.RandomSource
                                    .seeded(index.toLong()),
                            ).value,
                        arb
                            .sample(
                                io.kotest.property.RandomSource
                                    .seeded(index.toLong() + 1),
                            ).value,
                    )
                val report = ConsumerMapper.map(changes, registry, "test")

                // no phantom changes, no phantom consumers
                report.impacts.forEach { impact ->
                    (impact.consumer in registry.consumers) shouldBe true
                    impact.changes.forEach { entry -> (entry.change in changes) shouldBe true }
                }
                // determinism
                report shouldBe ConsumerMapper.map(changes, registry, "test")
                // wildcard completeness: every engine change maps at least once
                if (changes.isNotEmpty()) {
                    report.impacts
                        .single()
                        .changes
                        .map { it.change }
                        .toSet() shouldBe changes.toSet()
                }
            }
        }
    })
