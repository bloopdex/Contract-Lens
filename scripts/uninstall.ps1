# ContractLens uninstaller (Windows) — removes the install directory
# (%LOCALAPPDATA%\contractlens) and (opt-in) the PATH entry added by
# install.ps1. Never touches anything outside those two locations.

$ErrorActionPreference = "Stop"

$InstallDir = Join-Path $env:LOCALAPPDATA "contractlens"
if (Test-Path $InstallDir) {
    Remove-Item -Recurse -Force $InstallDir
    Write-Host "removed $InstallDir"
} else {
    Write-Host "nothing installed at $InstallDir"
}

$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -like "*$InstallDir*") {
    $Cleaned = ($UserPath -split ';' | Where-Object { $_ -and $_ -ne $InstallDir }) -join ';'
    [Environment]::SetEnvironmentVariable("Path", $Cleaned, "User")
    Write-Host "removed $InstallDir from the user PATH"
}
Write-Host "contractlens uninstalled"
