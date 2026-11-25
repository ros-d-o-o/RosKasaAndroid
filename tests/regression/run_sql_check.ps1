param(
    [ValidateSet("01", "02", "03", "04")]
    [string]$Step = "02",
    [string]$AppLabel = "manual",
    [string]$Conn = "GASTS/GASTS@ROS"
)

$ErrorActionPreference = "Stop"
$env:NLS_LANG = "SLOVENIAN_SLOVENIA.EE8MSWIN1250"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlDir = Join-Path $scriptDir "sql"
$resultsDir = Join-Path $scriptDir "results"
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }

$map = @{
    "01" = "01_seed_test_data_kasa.sql"
    "02" = "02_snapshot_and_compare.sql"
    "03" = "03_reset_for_rerun.sql"
    "04" = "04_cleanup_all_test_data.sql"
}

$sqlFile = Join-Path $sqlDir $map[$Step]
if (-not (Test-Path $sqlFile)) {
    throw "SQL datoteka ne obstaja: $sqlFile"
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outFile = Join-Path $resultsDir "${timestamp}_${AppLabel}_step${Step}.txt"

Write-Host "Poganjam $($map[$Step]) proti $Conn (NLS_LANG=$env:NLS_LANG)..." -ForegroundColor Cyan
$output = & sqlplus -S $Conn "@$sqlFile"
$output | Tee-Object -FilePath $outFile
Write-Host "`nRezultat shranjen v: $outFile" -ForegroundColor Green
