# ContractLens pre-merge check — the core of the reusable GitHub Action
# (action.yml). Runs the analysis the Action documents: snapshot the
# base document, snapshot the head document, and produce the report
# (impact with a registry, classified diff without). Exit 1 = breaking
# changes detected (unless CL_FAIL_ON_BREAKING=false).
#
# Inputs come exclusively from environment variables (no shell
# interpolation of untrusted values — SECURITY.md):
#   CL_JAR              path to the contractlens fat JAR (required)
#   CL_OLD_SPEC         base contract document (required)
#   CL_NEW_SPEC         head contract document (required)
#   CL_REGISTRY         consumer registry YAML (optional)
#   CL_CONTRACT         contract name (default: old spec's file stem)
#   CL_FAIL_ON_BREAKING "false" to report without failing (default true)
#   CL_REPORT           where to write the JSON report (optional;
#                       default: <work>/report.json)
#   CL_GITHUB_OUTPUT    GitHub outputs file (optional; local runs skip)
#   CL_GITHUB_STEP_SUMMARY  GitHub step summary file (optional)
#
# Everything else (snapshots, store) lives under a fresh working
# directory — the Action never writes next to the user's documents.

$ErrorActionPreference = "Stop"

foreach ($Required in @("CL_JAR", "CL_OLD_SPEC", "CL_NEW_SPEC")) {
    if (-not (Get-Item env:$Required -ErrorAction SilentlyContinue)) {
        Write-Error "action input missing: $Required"
        exit 1
    }
}
if (-not (Test-Path $env:CL_JAR)) {
    Write-Error "CL_JAR does not exist: $($env:CL_JAR)"
    exit 1
}

$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$TempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { $env:TEMP }
$Work = Join-Path $TempRoot "contractlens-action-$PID"
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Store = Join-Path $Work "store"
$Report = if ($env:CL_REPORT) { $env:CL_REPORT } else { Join-Path $Work "report.json" }
$Contract = if ($env:CL_CONTRACT) { $env:CL_CONTRACT } else { [IO.Path]::GetFileNameWithoutExtension($env:CL_OLD_SPEC) }

function Invoke-Cli([string[]]$CliArgs) {
    # stdout only: redirecting stderr with 2>&1 wraps native stderr
    # lines in ErrorRecords (PS 5.1) and $ErrorActionPreference=Stop
    # would turn the CLI's structured stderr logs into a failure.
    $Out = & $Java -jar $env:CL_JAR @CliArgs
    return @{ Code = $LASTEXITCODE; Out = ($Out -join "`n") }
}

# Base and head snapshots (explicit, stable identities — the Action
# does not depend on git state).
$r = Invoke-Cli @("snapshot", $env:CL_OLD_SPEC, "--store", $Store, "--name", $Contract, "--sha", ("a" * 40))
if ($r.Code -ne 0) { Write-Error "base snapshot failed: $($r.Out)"; exit $r.Code }
$r = Invoke-Cli @("snapshot", $env:CL_NEW_SPEC, "--store", $Store, "--name", $Contract, "--sha", ("b" * 40))
if ($r.Code -ne 0) { Write-Error "head snapshot failed: $($r.Out)"; exit $r.Code }
$OldSnap = Join-Path $Store "$Contract@$('a' * 40).snapshot.json"
$NewSnap = Join-Path $Store "$Contract@$('b' * 40).snapshot.json"

# The analysis: impact when a registry is given, classified diff
# otherwise. The CLI's exit code 1 = breaking changes detected.
$Breaking = $false
if ($env:CL_REGISTRY) {
    $r = Invoke-Cli @("impact", $OldSnap, $NewSnap, "--registry", $env:CL_REGISTRY, "--json")
    $Breaking = ($r.Code -eq 1)
    if ($r.Code -eq 2) { Write-Error "impact analysis failed: $($r.Out)"; exit 2 }
    $r.Out | Set-Content -Path $Report -Encoding utf8
} else {
    $r = Invoke-Cli @("diff", $OldSnap, $NewSnap, "--classify", "--json")
    $Breaking = ($r.Code -eq 1)
    if ($r.Code -eq 2) { Write-Error "diff analysis failed: $($r.Out)"; exit 2 }
    $r.Out | Set-Content -Path $Report -Encoding utf8
}

if ($env:CL_GITHUB_OUTPUT) {
    "report=$Report" | Out-File -FilePath $env:CL_GITHUB_OUTPUT -Append -Encoding utf8
    "breaking=$($Breaking.ToString().ToLowerInvariant())" | Out-File -FilePath $env:CL_GITHUB_OUTPUT -Append -Encoding utf8
}
if ($env:CL_GITHUB_STEP_SUMMARY) {
    $Summary = if ($Breaking) { "ContractLens: **breaking changes detected** ($Contract)`n`n" } else { "ContractLens: no breaking changes ($Contract)`n`n" }
    $Summary += Get-Content $Report -Raw
    $Summary | Out-File -FilePath $env:CL_GITHUB_STEP_SUMMARY -Append -Encoding utf8
}

if ($Breaking) {
    Write-Host "breaking changes detected - full report: $Report"
    if ($env:CL_FAIL_ON_BREAKING -ne "false") {
        Write-Error "ContractLens: breaking contract changes detected between base and head ($Contract). See the report: $Report"
        exit 1
    }
    Write-Host "CL_FAIL_ON_BREAKING=false: reporting without failing"
    exit 0
}
Write-Host "no breaking changes - report: $Report"
exit 0
