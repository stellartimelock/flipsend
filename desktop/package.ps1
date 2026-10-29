# Builds the portable Windows executable. It is unsigned. Do not commit the output.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root "desktop\StellarSend.slnx"))) {
    $root = Get-Location
}
dotnet publish (Join-Path $root "desktop\src\StellarSend.App\StellarSend.App.csproj") `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -o (Join-Path $root "dist")
$published = Join-Path $root 'dist\FlipSend.exe'
if (-not (Test-Path $published)) {
    $legacy = Join-Path $root 'dist\StellarSend.exe'
    if (Test-Path $legacy) { Copy-Item $legacy $published -Force }
}
Write-Host "Portable executable: $published"
