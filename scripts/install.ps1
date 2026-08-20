# ContractLens installer (Windows) — installs the fat JAR and a
# `contractlens` shim into %LOCALAPPDATA%\contractlens, then offers to
# add the shim directory to the user PATH.
#
# Usage (from the release bundle):
#   powershell -ExecutionPolicy Bypass -File install.ps1
#
# Requires a JRE 17+ (java on PATH or JAVA_HOME). The shim calls
# `java -jar`, so PATH setup is the only persistent change (and it is
# opt-in).

$ErrorActionPreference = "Stop"

$Bundle = Split-Path -Parent $MyInvocation.MyCommand.Path
$Version = [IO.Path]::GetFileName($Bundle).Replace("contractlens-", "")
$InstallDir = Join-Path $env:LOCALAPPDATA "contractlens"
$Jar = Get-ChildItem (Join-Path $Bundle "contractlens-*-all.jar") | Select-Object -First 1
if (-not $Jar) {
    Write-Error "release jar not found next to the installer"
    exit 1
}

# Verify the bundled checksum before installing anything.
$Sums = Join-Path $Bundle "SHA256SUMS"
if (Test-Path $Sums) {
    $Expected = (Select-String -Path $Sums -Pattern '^([0-9a-f]{64})\s+contractlens.*all\.jar$').Matches[0].Groups[1].Value
    $Actual = (Get-FileHash -Algorithm SHA256 $Jar.FullName).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected) {
        Write-Error "checksum verification FAILED for $($Jar.Name) - do not install"
        exit 1
    }
    Write-Host "checksum verified: $Actual"
} else {
    Write-Warning "no SHA256SUMS next to the installer - skipping checksum verification"
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item $Jar.FullName (Join-Path $InstallDir "contractlens.jar") -Force

$Shim = Join-Path $InstallDir "contractlens.ps1"
@"
# contractlens $Version shim (installed by install.ps1)
\$Java = if (\$env:JAVA_HOME) { Join-Path \$env:JAVA_HOME "bin\java.exe" } else { "java" }
& \$Java -jar "$(Join-Path $InstallDir "contractlens.jar")" @args
exit \$LASTEXITCODE
"@ | Set-Content -Path $Shim -Encoding utf8

Write-Host ""
Write-Host "installed: $InstallDir"
Write-Host "run:       & `"$Shim`" --help"
Write-Host ""
$Add = Read-Host "add $InstallDir to your user PATH? [y/N]"
if ($Add -match '^[yY]') {
    $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($UserPath -notlike "*$InstallDir*") {
        [Environment]::SetEnvironmentVariable("Path", "$UserPath;$InstallDir", "User")
        Write-Host "PATH updated (new terminals will see `"contractlens`")"
    }
}
Write-Host "uninstall: scripts\uninstall.ps1 (from the bundle) or delete $InstallDir"
