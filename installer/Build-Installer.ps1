param()

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$desktopProject = Join-Path $root "src\LocalBridge.Desktop\LocalBridge.Desktop.csproj"
$publishDir = Join-Path $PSScriptRoot "publish\win-x64"
$issFile = Join-Path $PSScriptRoot "LocalBridgeSetup.iss"
$outputDir = Join-Path $PSScriptRoot "output"
$finalSetup = Join-Path $outputDir "Localink-Setup.exe"
$desktopDrop = Join-Path (Split-Path -Parent $root) "Localink-Setup.exe"
$iscc = Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe"

Write-Host "Publishing Localink for Windows..." -ForegroundColor Cyan
dotnet publish $desktopProject -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -o $publishDir

if (-not (Test-Path $iscc)) {
    throw "Inno Setup compiler not found at $iscc"
}

Write-Host "Building installer..." -ForegroundColor Cyan
& $iscc $issFile

if (-not (Test-Path $finalSetup)) {
    throw "Installer output was not created."
}

Copy-Item $finalSetup $desktopDrop -Force
Write-Host "Installer ready: $finalSetup" -ForegroundColor Green
Write-Host "Copied to: $desktopDrop" -ForegroundColor Green
