# Run all 6 trading POCs in sequence. Requires JDK 21 on PATH.

$ErrorActionPreference = "Stop"

$pocs = @(
    @{ Name = "11. Order Matching";    File = "order-matching-poc\OrderMatchingPoc.java" },
    @{ Name = "12. Realtime P&L";      File = "realtime-pnl-poc\RealtimePnlPoc.java" },
    @{ Name = "13. Stop-Loss Engine";  File = "stop-loss-engine-poc\StopLossEnginePoc.java" },
    @{ Name = "14. Margin Engine";     File = "margin-engine-poc\MarginEnginePoc.java" },
    @{ Name = "15. Settlement";        File = "settlement-poc\SettlementPoc.java" },
    @{ Name = "16. Corporate Actions"; File = "corporate-actions-poc\CorporateActionsPoc.java" }
)

$root = $PSScriptRoot

$javaVersion = (& java --version) 2>&1 | Select-Object -First 1
Write-Host "Using: $javaVersion" -ForegroundColor Cyan
Write-Host ""

foreach ($p in $pocs) {
    $bar = "=" * 72
    Write-Host $bar -ForegroundColor Yellow
    Write-Host "  $($p.Name)" -ForegroundColor Yellow
    Write-Host $bar -ForegroundColor Yellow
    & java "$root\$($p.File)"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "POC failed: $($p.Name)" -ForegroundColor Red
        exit 1
    }
    Write-Host ""
}

Write-Host "All POCs ran successfully." -ForegroundColor Green
