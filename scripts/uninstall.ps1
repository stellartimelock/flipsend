# Removes the Explorer "Send to phone" command, the startup entry, and the copied executable.
$ErrorActionPreference = "Stop"
$destDir = Join-Path $env:LOCALAPPDATA "StellarSend"
$destinations = @(
    (Join-Path $destDir "FlipSend.exe"),
    (Join-Path $destDir "StellarSend.exe")
)
foreach ($dest in $destinations) {
    if (Test-Path $dest) {
        & $dest --unregister-shell
        & $dest --disable-startup
        Remove-Item $dest -Force
    }
}
if (Test-Path $destDir) {
    Remove-Item $destDir -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "FlipSend shell command and startup entry were removed."
