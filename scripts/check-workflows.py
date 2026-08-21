#!/usr/bin/env python
"""Validate the GitHub Actions workflow files (syntactic + semantic).

Why this exists (recorded first-hosted-run repairs, 2026-08-21):

1. PyYAML accepts YAML that GitHub's workflow parser rejects. The
   nightly pipeline shipped with an unquoted `recorded: ~39 min` colon
   in a step name — valid enough for PyYAML, a parse error for GitHub
   ("Invalid workflow file ... yaml syntax on line 45"), which also
   produced bogus failed run records on every push.
2. Workflow SEMANTICS are invisible to any YAML parser: a step with
   both `run:` and a dangling `with:` block is valid YAML but invalid
   workflow structure ("Unexpected value 'with'"). That exact bug was
   shipped once; this script catches the class.

Run from the repository root:  python scripts/check-workflows.py

Exit 0 when every workflow parses and every step has exactly one of
`run`/`uses` and `with` only accompanies `uses`.
"""

import glob
import sys

import yaml

WORKFLOWS = sorted(glob.glob(".github/workflows/*.yml")) + ["action.yml"]

errors = 0

for path in WORKFLOWS:
    try:
        doc = yaml.safe_load(open(path, encoding="utf-8"))
    except yaml.YAMLError as exc:
        print(f"{path}: YAML parse error: {exc}")
        errors += 1
        continue

    if not doc:
        print(f"{path}: empty document")
        errors += 1
        continue

    # action.yml is an action MANIFEST (top-level `runs:` with steps),
    # not a workflow (top-level `jobs:`). Validate each shape.
    if path.endswith("action.yml"):
        runs = doc.get("runs", {})
        if not isinstance(runs, dict) or not runs.get("steps"):
            print(f"{path}: an action manifest needs a top-level runs: with steps")
            errors += 1
            continue
        steps = [
            (f"action step {index}", step)
            for index, step in enumerate(runs["steps"])
        ]
    else:
        jobs = doc.get("jobs", {})
        if not isinstance(jobs, dict) or not jobs:
            print(f"{path}: no jobs found")
            errors += 1
            continue
        steps = []
        for job_name, job in jobs.items():
            for index, step in enumerate(job.get("steps", [])):
                steps.append((f"job {job_name} step {index}", step))

    for label, step in steps:
        has_run = "run" in step
        has_uses = "uses" in step
        has_with = "with" in step
        if has_run and has_uses:
            print(f"{path}: {label}: both run and uses")
            errors += 1
        if has_with and not has_uses:
            print(f"{path}: {label}: with without uses")
            errors += 1
        if not has_run and not has_uses:
            print(f"{path}: {label}: neither run nor uses")
            errors += 1

if errors:
    print(f"{errors} workflow error(s)")
    sys.exit(1)

print(f"{len(WORKFLOWS)} workflow file(s) OK")
