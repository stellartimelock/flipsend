# Copies the portable FlipSend executable into %LOCALAPPDATA%\StellarSend
# and registers the Explorer "Send to phone" command. Run uninstall.ps1 to remove both.
param(
    [switch]$Startup,
    [switch]$NoStartup
)

$ErrorActionPreference = "Stop"
$destDir = Join-Path $env:LOCALAPPDATA "StellarSend"
$dest = Join-Path $destDir "FlipSend.exe"
$candidates = @(
    (Join-Path $PSScriptRoot "..\dist\FlipSend.exe"),
    (Join-Path $PSScriptRoot "FlipSend.exe"),
    (Join-Path (Get-Location) "FlipSend.exe"),
    (Join-Path $PSScriptRoot "..\dist\StellarSend.exe"),
    (Join-Path $PSScriptRoot "StellarSend.exe"),
    (Join-Path (Get-Location) "StellarSend.exe")
)
$source = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $source) {
    throw "Build the portable exe first. See the README packaging section."
}

New-Item -ItemType Directory -Force -Path $destDir | Out-Null
Copy-Item $source $dest -Force
& $dest --register-shell
if (-not $NoStartup) {
    & $dest --enable-startup
}
Write-Host "Installed to $dest"
Write-Host "Explorer command: Send to phone. Remove it with scripts\uninstall.ps1."
