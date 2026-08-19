// Pure consumer mapping (Phase 3).
//
// Consumes the validated registry and the structural change set and
// produces deterministic per-consumer impacts. No IO, no parsing, no
// severity decisions — the core mapping logic stays a pure function of
// its inputs.
//
// Semantics (ADR-002 + the Phase 3 execution scope):
//   - a change maps to a consumer when the consumer's contract selector
//     names the diffed contract AND an operation selector matches the
//     canonical identity of the changed operation
//   - overlapping selectors never produce duplicate records
//   - unmatched changes are not errors — they stay visible in the report
//   - consumers are grouped by id; within a consumer, changes keep the
//     engine's changeOrder
//
// Complexity stays linear-ish: consumers are filtered by contract, then
// each change is tested against that consumer's (small) selector list.

package dev.bloopdex.contractlens.core.impact

import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.changeOrder
import dev.bloopdex.contractlens.core.registry.Consumer
import dev.bloopdex.contractlens.core.registry.ConsumerRegistry

const val REASON_ALL_OPERATIONS = "consumer declares this contract (all operations)"
const val REASON_THIS_OPERATION = "consumer declares this operation"

object ConsumerMapper {
    fun map(
        changes: List<ContractChange>,
        registry: ConsumerRegistry,
        contract: String,
    ): ImpactReport {
        val ordered = changes.sortedWith(changeOrder)
        val impacts = mutableListOf<ConsumerImpact>()
        for (consumer in registry.consumers.filter { it.contract == contract }.sortedBy { it.id }) {
            val matched = mutableListOf<ImpactedChange>()
            for (change in ordered) {
                for (operation in affectedOperations(change)) {
                    if (consumer.selectors.any { it.matches(operation.method, operation.pathIdentity) }) {
                        matched += ImpactedChange(operation = operation, change = change, reason = reasonFor(consumer))
                    }
                }
            }
            if (matched.isNotEmpty()) {
                // One change can legitimately match one consumer through
                // several equivalent selectors; deduplicate by the actual
                // (change, operation) association, not by match count.
                impacts += ConsumerImpact(consumer = consumer, changes = matched.distinctBy { it.change to it.operation })
            }
        }
        return ImpactReport(contract = contract, changes = ordered, impacts = impacts)
    }

    private fun reasonFor(consumer: Consumer): String =
        if (consumer.selectors.any { it.matchesAll }) REASON_ALL_OPERATIONS else REASON_THIS_OPERATION

    /** Number of distinct (change, consumer) associations across all impacts. */
    fun mappedChangeCount(impacts: List<ConsumerImpact>): Int =
        impacts.sumOf { impact ->
            impact.changes
                .map { it.change }
                .distinct()
                .size
        }
}
