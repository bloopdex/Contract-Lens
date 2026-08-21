# Security policy

ContractLens is a local-first CLI. It never phones home: there is no
network code path in the tool. The security review of its surfaces lives
in [docs/security.md](docs/security.md); this file defines the
vulnerability policy and reporting.

## Reporting a vulnerability

Report suspected vulnerabilities privately to the maintainer (the
repository owner). Do not open a public issue for a suspected
vulnerability. Please include:

- the affected version(s);
- a minimal reproducer;
- the impact you believe it has.

## Dependency vulnerability policy

Scanning runs in CI (OSV-Scanner over the Gradle lockfiles, plus
GitHub's dependency graph and Dependabot) and locally with the
documented docker invocation (see below). Gradle dependency resolution
integrity is enforced separately with
`--dependency-verification=strict` against the committed
`gradle/verification-metadata.xml` (SHA-256 of every resolved artifact).

Failure policy:

| Severity | Action |
|---|---|
| Critical | Blocking. Fix or upgrade before merge; no exceptions without a documented, time-bounded waiver in `docs/security.md`. |
| High | Blocking. Fix or upgrade before merge. A waiver must state: reachability (is the vulnerable code path used?), exploitability (is it reachable from untrusted input?), and a deadline. |
| Medium | Does not block merges, but is listed in the scan output and triaged in the next iteration. |
| Low | Recorded, not blocking. |

Waivers are explicit, documented, time-bounded, and reviewable — never
silent suppressions. A scanner finding is never hidden to make CI green.

## Local scanning

```
# from the repository root (lockfiles are committed, so scans are reproducible)
# --config applies the documented waivers in osv-scanner.toml — omit it
# and the scan reports filtered findings as failures
docker run --rm -v "${PWD}:/src" -w /src ghcr.io/google/osv-scanner scan --config=osv-scanner.toml -r .
```

The same tool + the same lockfiles in CI = the same policy.

## CI security

- PR workflows run with `permissions: contents: read` only — untrusted
  PR code never gets write access.
- The release workflow (`contents: write`) runs ONLY on `v*.*.*` tag
  pushes, which only maintainers can create; it never runs on PRs.
- Actions are pinned; no script interpolates untrusted input into a
  shell (inputs are passed via environment variables and arguments).
