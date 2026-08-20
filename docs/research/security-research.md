# Research record — the security strategy

## Question

Contract documents are untrusted input. What can a malicious or
pathological contract do to the tool, and which defenses are worth
their cost?

## Research

The threat model was recorded before implementation (the canonical
attack surface for YAML/JSON-based tools):

- malformed YAML/JSON; YAML anchor bombs ("billion laughs") and alias
  expansion — the historically hostile YAML surface;
- deeply nested schemas and recursive `$ref` cycles (stack/memory
  exhaustion);
- multi-megabyte documents (resource exhaustion);
- path traversal via contract names, registry paths, and file
  arguments;
- secrets in spec descriptions/examples leaking into snapshots,
  reports, and logs;
- dependency vulnerabilities.

Defense design decisions, each with its evidence:

- **Bounds before parse, not during:** size (10 MB) and depth (64)
  limits applied *before* any parser runs, with typed failures —
  because partial parses of oversized documents are where parsers
  misbehave.
- **Redaction by construction, not by sanitization:** the canonical
  model never captures descriptions/examples — there is nothing to
  sanitize downstream, and no sanitizer to bypass. Chosen over a
  redaction pass because omission is provable by test; sanitization
  is only ever as good as its last regex.
- **Local references only:** remote resolution implies network code
  and trust decisions; rejected for the core with a typed error.
- **Parser hardening by fuzzing, two layers:** a seeded,
  invariant-checking harness (controlled outcomes + determinism) and
  Jazzer coverage-guided targets for the five parser boundaries plus
  snapshot round-trip. The diff/classifier/mapper were deliberately
  not targeted — their inputs are typed domain data, not bytes, and
  their invariants are already pinned at scale. The Jazzer evaluation
  itself was deferred once (coverage-guided fuzzing adds most value
  as a bounded, long-running CI job rather than a local test) and then
  executed exactly as deferred.
- **Supply chain:** dependency verification metadata (SHA-256, strict
  resolution) + lockfiles + OSV-Scanner with a severity policy, and
  SHA-256-checksummed release artifacts.

## Alternatives

- **Sanitization pass over prose instead of omission:** rejected —
  see above.
- **Remote `$ref` resolution with allowlists:** rejected — an
  allowlist is a network code path plus a trust decision, for
  documents whose local form already covers the use cases.
- **Native-only fuzzing (Jazzer alone):** rejected — the seeded
  harness pins deterministic *invariants* over the committed corpus at
  scale; Jazzer finds *new* inputs. They are complementary, and both
  are kept.

## Decision

The defenses above, in the layered order documented in
[security.md](../security.md) (threat → boundary → defense →
verification), with every claim test-pinned.

## Why

Each defense is the cheapest option that makes its failure mode loud
instead of silent: bounds make exhaustion a typed error; omission
makes leakage impossible rather than unlikely; local-only refs remove
a network trust decision; fuzzing turns parser robustness into a
property; supply-chain verification turns dependency integrity into a
CI gate.

## Implementation

- `Limits` (10 MB) + per-adapter depth guards; `SnapshotStore`
  sanitization; the redaction-by-omission model; typed error model;
  `:fuzz` module (six Jazzer targets); the seeded harnesses;
  dependency verification metadata + lockfiles + `osv-scanner.toml`.

## Verification

- Recorded fuzz sweeps: 1,000,000 parser executions + 200,000
  classifier iterations, clean (re-run clean after the dependency
  upgrades); Jazzer: 2,573,262 executions across six targets, 0
  crashes.
- Regression tests: path escape, redaction, `INPUT_TOO_LARGE` at
  every boundary, snapshot tampering.
- The first OSV scan found real findings (jackson 8.7/8.1 Highs and
  more) — all fixed with documented resolution rules; post-fix scan
  clean with one time-bounded waiver (details in
  [security.md](../security.md)).

## Consequences

- Honest gaps remain recorded: symlink-specific handling and
  permission-edge testing are open follow-ups; CI execution of the
  scans awaits hosting.
- The 10 MB / depth-64 bounds are documented as deliberate — a
  real-world API dump (~73 KB) is far under them
  ([limitations.md](../limitations.md)).
- Fuzz timeouts are never raised to make a failure disappear; a found
  crash becomes a committed regression seed.
