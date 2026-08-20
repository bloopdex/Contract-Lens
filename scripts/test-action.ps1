# End-to-end test of the reusable pre-merge Action (action.yml) — the
# locally executable equivalent of "the Action blocks a breaking PR in
# a sample repo" (Phase 6 page). Runs scripts/action-check.ps1 against
# a generated sample pair, exactly as the Action would invoke it:
#
#   1. pass case: an additive (non-breaking) change → exit 0,
#      breaking=false, report produced
#   2. block case: a breaking change → exit 1, breaking=true
#   3. registry case: breaking change + registry → impact report
#      (affectedConsumers present)
#   4. fail-on-breaking=false: the breaking change reports but exits 0
#
# GitHub-only features (step summaries, PR comments, GITHUB_OUTPUT) are
# exercised where possible via CL_GITHUB_OUTPUT/CL_GITHUB_STEP_SUMMARY
# files; the PR-comment step itself is a GitHub-runner-only feature and
# is documented as such.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Check = Join-Path $Root "scripts\action-check.ps1"
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }

Push-Location $Root
Write-Host "=== building the fat JAR (what the Action builds) ==="
.\gradlew.bat :cli:shadowJar --console=plain
if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 }
$Jar = Get-ChildItem "cli\build\libs\contractlens-*-all.jar" | Select-Object -First 1
Pop-Location
Write-Host "  jar: $($Jar.FullName)"

$Fixture = Join-Path $Root "build\action-e2e"
if (Test-Path $Fixture) { Remove-Item -Recurse -Force $Fixture }
New-Item -ItemType Directory -Force -Path $Fixture | Out-Null

$Base = Join-Path $Fixture "base.yaml"
$Additive = Join-Path $Fixture "additive.yaml"
$Breaking = Join-Path $Fixture "breaking.yaml"
$Registry = Join-Path $Fixture "registry.yaml"

@"
openapi: 3.0.3
info:
  title: Action API
  version: 1.0.0
paths:
  /ping:
    get:
      responses:
        '200':
          description: ok
          content:
            application/json:
              schema:
                type: object
                properties:
                  status:
                    type: string
"@ | Set-Content -Path $Base -Encoding utf8
# additive: a new property is non-breaking
(Get-Content $Base -Raw).Replace("                  status:`n                    type: string", "                  status:`n                    type: string`n                  extra:`n                    type: string") | Set-Content -Path $Additive -Encoding utf8
# breaking: the response property type changes
(Get-Content $Base -Raw).Replace("type: string", "type: integer") | Set-Content -Path $Breaking -Encoding utf8
# ascii: PowerShell 5.1's utf8 encoding writes a BOM, which kaml rejects
"version: 1`nconsumers:`n  - id: ping-watcher`n    kind: service`n    contract: action-api`n    operations: ['*']`n" | Set-Content -Path $Registry -Encoding ascii

$GitHubOutput = Join-Path $Fixture "github-output.txt"
$StepSummary = Join-Path $Fixture "step-summary.md"
$Failures = 0

function Invoke-ActionCase(
    [string]$Name,
    [string]$Old,
    [string]$New,
    [string]$Registry,
    [string]$FailOnBreaking,
    [int]$ExpectedCode,
    [string]$ExpectedBreaking
) {
    $env:CL_JAR = $Jar.FullName
    $env:CL_OLD_SPEC = $Old
    $env:CL_NEW_SPEC = $New
    $env:CL_REGISTRY = $Registry
    $env:CL_CONTRACT = "action-api"
    $env:CL_FAIL_ON_BREAKING = $FailOnBreaking
    $env:CL_REPORT = Join-Path $Fixture "$Name-report.json"
    $env:CL_GITHUB_OUTPUT = $GitHubOutput
    $env:CL_GITHUB_STEP_SUMMARY = $StepSummary
    Remove-Item $GitHubOutput, $StepSummary -ErrorAction SilentlyContinue

    # The child writes its findings to stderr BY DESIGN (the breaking
    # case is expected control flow). $ErrorActionPreference=Stop must
    # not turn the child's stderr into a wrapper termination.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & powershell -NoProfile -File $Check *> "$Fixture\$Name.log"
    $Code = $LASTEXITCODE
    $ErrorActionPreference = $previous

    $Breaking = "false"
    if (Test-Path $GitHubOutput) {
        $Breaking = (Select-String -Path $GitHubOutput -Pattern '^breaking=(true|false)$').Matches.Groups[1].Value
    }
    if ($Code -ne $ExpectedCode -or $Breaking -ne $ExpectedBreaking) {
        Write-Error "FAIL ${Name}: expected exit $ExpectedCode / breaking=$ExpectedBreaking, got exit $Code / breaking=$Breaking (log: ${Fixture}\$($Name).log)"
        $script:Failures++
    } else {
        Write-Host "PASS $Name (exit $Code, breaking=$Breaking)"
    }
    if (-not (Test-Path $env:CL_REPORT)) {
        Write-Error "FAIL ${Name}: no report produced"
        $script:Failures++
    }
    return $env:CL_REPORT
}

Write-Host "=== case 1: pass (additive change, no registry) ==="
$R1 = Invoke-ActionCase "pass" $Base $Additive "" "true" 0 "false"
if ((Get-Content $R1 -Raw) -notmatch '"format":"contractlens-diff"') { Write-Error "FAIL pass: expected a classified-diff report"; $Failures++ }

Write-Host "=== case 2: block (breaking change, no registry) ==="
$R2 = Invoke-ActionCase "block" $Base $Breaking "" "true" 1 "true"
if ((Get-Content $R2 -Raw) -notmatch '"breaking":1') { Write-Error "FAIL block: expected a breaking summary"; $Failures++ }

Write-Host "=== case 3: registry (breaking change + impact mapping) ==="
$R3 = Invoke-ActionCase "registry" $Base $Breaking $Registry "true" 1 "true"
$R3Text = Get-Content $R3 -Raw
if ($R3Text -notmatch '"affectedConsumers":1') { Write-Error "FAIL registry: expected affectedConsumers 1"; $Failures++ }
if ($R3Text -notmatch '"ping-watcher"') { Write-Error "FAIL registry: expected the consumer id"; $Failures++ }

Write-Host "=== case 4: report-without-failing (breaking, fail-on-breaking=false) ==="
$R4 = Invoke-ActionCase "no-fail" $Base $Breaking "" "false" 0 "true"
if ((Get-Content $StepSummary -Raw) -notmatch "breaking changes detected") { Write-Error "FAIL no-fail: expected a step summary"; $Failures++ }

if ($Failures -gt 0) {
    Write-Error "action e2e: $Failures case(s) failed"
    exit 1
}
Write-Host ""
Write-Host "action e2e: all four cases passed (pass / block / registry / no-fail)"
Write-Host "note: the PR-comment step itself is GitHub-runner-only (gh + GITHUB_TOKEN);"
Write-Host "      it is exercised by the nightly action-e2e job once the repo is hosted."
exit 0
