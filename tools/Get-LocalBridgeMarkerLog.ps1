param(
    [ValidateSet("DISCOVERY", "PAIRING", "RECONNECT", "TRANSFER", "SESSION", "STORAGE", "ALL")]
    [string]$Marker = "ALL",
    [int]$TailLines = 200
)

$logPath = Join-Path $env:LOCALAPPDATA "LocalBridge\Desktop\desktop.log"

if (-not (Test-Path -LiteralPath $logPath)) {
    Write-Warning "LocalBridge desktop log file was not found: $logPath"
    exit 1
}

$lines = Get-Content -LiteralPath $logPath -Tail $TailLines

if ($Marker -eq "ALL") {
    $lines
    exit 0
}

$needle = "[$Marker]"
$lines | Where-Object { $_.Contains($needle) }
