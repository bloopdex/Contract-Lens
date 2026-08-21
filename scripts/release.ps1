# ContractLens release bundle script (Windows host), ADR-007.
#
# Builds the fat JAR and assembles the distributable bundle:
#   dist/contractlens-<version>/
#       contractlens-<version>-all.jar
#       SHA256SUMS
#       install.ps1 / install.sh / uninstall.ps1
#       CHANGELOG.md
#       LICENSE
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 1.0.0
#
# Publication is deliberately NOT part of this script. `dist/` is
# gitignored: the bundle is generated locally (verification,
# dogfooding) and by CI (publication). Pushing a `vX.Y.Z` tag triggers
# the release workflow (.github/workflows/release.yml), which validates
# the tag against build.gradle.kts, rebuilds this bundle, verifies its
# checksums, and creates the GitHub Release (docs/release.md).
# Generated artifacts are never committed.

param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# Verify the build-file version matches the release version (version
# consistency is a release-checklist item).
$BuildFile = Get-Content (Join-Path $Root "build.gradle.kts") -Raw
if ($BuildFile -notmatch 'version\s*=\s*"' + [regex]::Escape($Version) + '"') {
    Write-Error "build.gradle.kts version does not match -Version $Version - bump it first."
    exit 1
}

Push-Location $Root

# Full validation before any artifact exists: clean build (compile,
# ktlint, all tests) plus the coverage gate.
Write-Host "=== validation: clean build + coverage gate ==="
.\gradlew.bat clean build koverVerify --console=plain
if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 }

# The primary artifact (ADR-007).
Write-Host "=== packaging: shadow fat JAR ==="
.\gradlew.bat :cli:shadowJar --console=plain
if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 }

$Jar = Join-Path $Root "cli\build\libs\contractlens-$Version-all.jar"
if (-not (Test-Path $Jar)) {
    Write-Error "expected fat JAR not found: $Jar"
    Pop-Location
    exit 1
}

$Bundle = Join-Path $Root "dist\contractlens-$Version"
New-Item -ItemType Directory -Force -Path $Bundle | Out-Null
Copy-Item $Jar (Join-Path $Bundle "contractlens-$Version-all.jar")
Copy-Item (Join-Path $Root "scripts\install.ps1") (Join-Path $Bundle "install.ps1")
Copy-Item (Join-Path $Root "scripts\install.sh") (Join-Path $Bundle "install.sh")
Copy-Item (Join-Path $Root "scripts\uninstall.ps1") (Join-Path $Bundle "uninstall.ps1")
Copy-Item (Join-Path $Root "CHANGELOG.md") (Join-Path $Bundle "CHANGELOG.md")
Copy-Item (Join-Path $Root "LICENSE") (Join-Path $Bundle "LICENSE")

# Checksums for every artifact, then verify them before anything else.
$SumFile = Join-Path $Bundle "SHA256SUMS"
$Lines = foreach ($Name in @("contractlens-$Version-all.jar", "install.ps1", "install.sh", "uninstall.ps1", "CHANGELOG.md", "LICENSE")) {
    $Hash = (Get-FileHash -Algorithm SHA256 (Join-Path $Bundle $Name)).Hash.ToLowerInvariant()
    "$Hash  $Name"
}
$Lines | Out-File -Encoding ascii $SumFile
foreach ($Line in Get-Content $SumFile) {
    $Hash, $Name = $Line -split '\s+', 2
    $Actual = (Get-FileHash -Algorithm SHA256 (Join-Path $Bundle $Name)).Hash.ToLowerInvariant()
    if ($Actual -ne $Hash) {
        Write-Error "checksum mismatch for ${Name}: expected $Hash, got $Actual"
        Pop-Location
        exit 1
    }
}
Write-Host "SHA256SUMS written and verified ($($Lines.Count) artifacts)"

# Smoke test the artifact AS AN ARTIFACT (docs/release.md): the exact
# jar users receive, not the source tree. A generated spec pair with a
# breaking change covers snapshot/diff/impact/generated-diff/signal,
# exit codes, JSON output, and the metrics event.
Write-Host "=== smoke test of the artifact ==="
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$JarPath = Join-Path $Bundle "contractlens-$Version-all.jar"
function Invoke-Cli([string[]]$CliArgs) {
    # stdout only: redirecting stderr with 2>&1 wraps native stderr
    # lines in ErrorRecords (PS 5.1) and $ErrorActionPreference=Stop
    # would turn the CLI's structured stderr logs into a failure.
    $Out = & $Java -jar $JarPath @CliArgs
    return @{ Code = $LASTEXITCODE; Out = ($Out -join "`n") }
}

$Smoke = Join-Path $Root "build\release-smoke"
New-Item -ItemType Directory -Force -Path $Smoke | Out-Null
$OldSpec = Join-Path $Smoke "ping.yaml"
$NewSpec = Join-Path $Smoke "ping-breaking.yaml"
@"
openapi: 3.0.3
info:
  title: Smoke API
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
"@ | Set-Content -Path $OldSpec -Encoding utf8
(Get-Content $OldSpec -Raw).Replace("type: string", "type: integer") | Set-Content -Path $NewSpec -Encoding utf8
$Registry = Join-Path $Smoke "registry.yaml"
# ascii: PowerShell 5.1's utf8 encoding writes a BOM, which kaml rejects
"version: 1`nconsumers:`n  - id: smoke-watcher`n    kind: service`n    contract: smoke`n    operations: ['*']`n" | Set-Content -Path $Registry -Encoding ascii

$ShaA = "a" * 40
$ShaB = "b" * 40
$Store = Join-Path $Smoke "store"

$r = Invoke-Cli @("--version")
if ($r.Code -ne 0 -or $r.Out -notmatch [regex]::Escape($Version)) {
    Write-Error "smoke: --version failed ('$($r.Out)', exit $($r.Code))"
    Pop-Location
    exit 1
}
Write-Host "  --version: $($r.Out)"

$r = Invoke-Cli @("--help")
if ($r.Code -ne 0) { Write-Error "smoke: --help exited $($r.Code)"; Pop-Location; exit 1 }

$r = Invoke-Cli @("snapshot", $OldSpec, "--store", $Store, "--name", "smoke", "--sha", $ShaA)
if ($r.Code -ne 0) { Write-Error "smoke: snapshot old failed: $($r.Out)"; Pop-Location; exit 1 }
$r = Invoke-Cli @("snapshot", $NewSpec, "--store", $Store, "--name", "smoke", "--sha", $ShaB)
if ($r.Code -ne 0) { Write-Error "smoke: snapshot new failed: $($r.Out)"; Pop-Location; exit 1 }
$OldSnap = Join-Path $Store "smoke@$ShaA.snapshot.json"
$NewSnap = Join-Path $Store "smoke@$ShaB.snapshot.json"

$r = Invoke-Cli @("diff", $OldSnap, $NewSnap, "--classify")
if ($r.Code -ne 1) { Write-Error "smoke: diff --classify expected exit 1 (breaking), got $($r.Code)"; Pop-Location; exit 1 }
if ($r.Out -notmatch "1 breaking") { Write-Error "smoke: expected '1 breaking' in diff output"; Pop-Location; exit 1 }

$r = Invoke-Cli @("diff", $OldSnap, $NewSnap, "--classify", "--json")
if ($r.Code -ne 1 -or $r.Out -notmatch '"version":2') { Write-Error "smoke: diff --json failed (exit $($r.Code))"; Pop-Location; exit 1 }

$r = Invoke-Cli @("impact", $OldSnap, $NewSnap, "--registry", $Registry, "--json")
if ($r.Code -ne 1 -or $r.Out -notmatch '"affectedConsumers":1') {
    Write-Error "smoke: impact failed (exit $($r.Code)): $($r.Out)"
    Pop-Location
    exit 1
}

$r = Invoke-Cli @("generated-diff", $OldSnap, $NewSnap, "--style", "ts", "--classify")
if ($r.Code -ne 1) { Write-Error "smoke: generated-diff expected exit 1, got $($r.Code)"; Pop-Location; exit 1 }

$r = Invoke-Cli @("signal", $OldSnap, $NewSnap, "--registry", $Registry)
if ($r.Code -ne 1 -or $r.Out -notmatch '"format":"contractlens-signal"') {
    Write-Error "smoke: signal failed (exit $($r.Code))"
    Pop-Location
    exit 1
}

$r = Invoke-Cli @("signal", $OldSnap, $NewSnap, "--registry", $Registry, "--verbose")
if ($r.Code -ne 1 -or $r.Out -notmatch "contractlens-signal") {
    Write-Error "smoke: signal --verbose failed (exit $($r.Code))"
    Pop-Location
    exit 1
}
Write-Host "  snapshot/diff/impact/generated-diff/signal: exit codes and outputs verified (breaking pair)"

# The tree must stay clean: dist/ and build/ are ignored, nothing else
# may change.
Pop-Location
$Dirty = git -C $Root status --porcelain
if ($Dirty) {
    Write-Error "the release script must never modify the repository - unexpected changes:`n$Dirty"
    exit 1
}

Write-Host ""
Write-Host "Release bundle: $Bundle"
Write-Host "  contractlens-$Version-all.jar  ($(Get-Item $JarPath | ForEach-Object Length) bytes)"
Write-Host "  SHA256SUMS  (verified)"
Write-Host "  working tree clean"
Write-Host ""
Write-Host "Next steps (docs/release.md):"
Write-Host "  - tag:  git tag -a v$Version -m 'Release v$Version'"
Write-Host "  - push: git push origin main; git push origin v$Version"
Write-Host "    (the tag push triggers the GitHub Actions release workflow -"
Write-Host "     dist/ stays uncommitted)"
