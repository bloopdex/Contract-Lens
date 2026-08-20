# Research records

Each record below preserves the reasoning behind a load-bearing
decision: the question that needed answering, what was actually
investigated (with evidence — tool documentation, ecosystem data,
measurements, repository history), the alternatives genuinely
considered, what was chosen and why, what was built, how it was
verified, and the consequences.

Two honesty rules govern these records:

1. **Nothing is invented.** If the historical record does not contain
   enough evidence for a section, the record says so instead of
   filling the gap. Where a decision was made during implementation
   rather than in advance, that is stated.
2. **A record is not a victory lap.** Rejected alternatives and
   negative findings are preserved — they are usually the most
   reusable part.

The records:

| Record | Question it preserves | Resulting decision |
|---|---|---|
| [contract-compatibility.md](contract-compatibility.md) | Is there a real gap between existing diff tools and "who breaks?" | Approaches A–E compared; B (+D later) chosen — the whole product thesis |
| [canonical-model.md](canonical-model.md) | How to model contracts so every format shares one engine | ADR-001/ADR-004 model; ADR-003 snapshots |
| [classification-strategy.md](classification-strategy.md) | What makes a change breaking, and what do we do when evidence runs out | ADR-001 three-verdict ruleset |
| [generated-client-strategy.md](generated-client-strategy.md) | How to diff generated clients without parsing generated source | ADR-006 projection |
| [extended-format-strategy.md](extended-format-strategy.md) | How GraphQL and JSON Schema fit the canonical model | ADR-004 amendment (adapters on the shared engine) |
| [usage-graph.md](usage-graph.md) | Can classification use which fields consumers actually read? | Format implemented; wiring deferred with evidence |
| [security-research.md](security-research.md) | What can a malicious contract do to a parser? | The threat model → the limits, redaction, fuzz, and supply-chain layers |
| [testing-strategy.md](testing-strategy.md) | How do you pin a compatibility ruleset against regressions? | Fixture-driven testing + property tests + fuzzing |

Performance is its own research record: [performance.md](../performance.md)
(question → method → measured baseline → the no-optimization decision →
consequences).

The full depth of the historical research — per-question tasks, dated
completion evidence, and the delivery records — lives in the project's
external research notes. These files are the distilled, citable
version; the ADRs in [adr/](../adr/) are the decisions they produced.
