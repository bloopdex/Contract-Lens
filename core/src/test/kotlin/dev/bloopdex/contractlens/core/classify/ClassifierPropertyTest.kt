// Property-based invariants of the classifier:
//   1. determinism — identical inputs, identical reports
//   2. totality — every change is classified exactly once, in changeOrder
//   3. verdict/semver consistency — review never carries a semver label
//   4. purity — inputs are never mutated (verdicts stay null on them)
//   5. engine integration — every change the real engine emits is
//      classified without failure, and the two surface-contextual
//      lookups never make classification non-total
//   6. rename pairing is deterministic

package dev.bloopdex.contractlens.core.classify

import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.diff.changeOrder
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.surfaceArb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

class ClassifierPropertyTest :
    FunSpec({

        fun assertValidReport(
            report: ClassificationReport,
            inputCount: Int,
        ) {
            report.changes.size shouldBe inputCount
            report.changes.forEach { entry ->
                entry.change.verdict shouldBe
                    entry.verdict.name
                        .lowercase()
                        .replace('_', '-')
                if (entry.verdict == Verdict.REVIEW) entry.semver shouldBe null
            }
        }

        test("determinism: classifying the same inputs twice yields identical reports") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                Classifier.classify(changes, old, new) shouldBe Classifier.classify(changes, old, new)
            }
        }

        test("totality: every engine change is classified exactly once, in changeOrder") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                val report = Classifier.classify(changes, old, new)
                assertValidReport(report, changes.size)
                // Classified changes are verdict-filled copies of the
                // inputs; compare with the verdict stripped.
                report.changes.map { it.change.copy(verdict = null) } shouldBe changes.sortedWith(changeOrder)
            }
        }

        test("purity: the classifier never mutates its inputs") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                Classifier.classify(changes, old, new)
                changes.forEach { change -> change.verdict shouldBe null }
            }
        }

        test("verdict/semver consistency across generated diffs") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val report = Classifier.classify(DiffEngine.diff(old, new), old, new)
                report.changes.forEach { entry ->
                    when (entry.verdict) {
                        Verdict.REVIEW -> entry.semver shouldBe null
                        Verdict.BREAKING -> entry.semver shouldBe SemverLevel.MAJOR
                        Verdict.NON_BREAKING -> (entry.semver == SemverLevel.MINOR || entry.semver == SemverLevel.PATCH) shouldBe true
                    }
                }
                report.summary.total shouldBe report.summary.breaking + report.summary.nonBreaking + report.summary.review
            }
        }

        test("rename pairing is deterministic: shuffled inputs classify identically") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                val forward = Classifier.classify(changes, old, new)
                val shuffled = Classifier.classify(changes.shuffled(), old, new)
                forward shouldBe shuffled
            }
        }

        test("summary semver is the maximum of the per-change semver labels") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val report = Classifier.classify(DiffEngine.diff(old, new), old, new)
                val expected = report.changes.mapNotNull { it.semver }.maxByOrNull { it.rank }
                report.summary.semver shouldBe expected
            }
        }
    })
