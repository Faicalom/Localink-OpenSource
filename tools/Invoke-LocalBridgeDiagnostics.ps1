param(
    [int]$ApiPort = 45870,
    [int]$TailLines = 40
)

$appRoot = Join-Path $env:LOCALAPPDATA "LocalBridge\Desktop"
$logPath = Join-Path $appRoot "desktop.log"
$statusUrl = "http://127.0.0.1:$ApiPort/api/connection/status"

Write-Host "LocalBridge diagnostics"
Write-Host "Status endpoint: $statusUrl"
Write-Host "Desktop log file: $logPath"
Write-Host ""

try {
    $status = Invoke-RestMethod -Method Get -Uri $statusUrl -TimeoutSec 5
    Write-Host "Connection status"
    $status | ConvertTo-Json -Depth 6
}
catch {
    Write-Warning "Could not query the desktop status endpoint: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "Recent desktop log entries"
if (Test-Path -LiteralPath $logPath) {
    Get-Content -LiteralPath $logPath -Tail $TailLines
}
else {
    Write-Warning "Desktop log file was not found yet."
}
