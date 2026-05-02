param(
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$HostChromeProfileDir = 'C:\chrome-jd-profile'

function Write-Step {
    param(
        [string]$Message,
        [string]$Color = 'White'
    )
    Write-Host $Message -ForegroundColor $Color
}

function Hold-Window {
    if (-not $NoPause) {
        Read-Host "`nPress Enter to close"
    }
}

function Stop-MatchingProcess {
    param(
        [string]$Name,
        [string]$CommandLinePattern,
        [string]$Label
    )

    $processes = Get-CimInstance Win32_Process -Filter "name = '$Name'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like $CommandLinePattern }

    if (-not $processes) {
        Write-Step "$Label is not running." Green
        return
    }

    foreach ($process in $processes) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Step "Stopped $Label process $($process.ProcessId)." Green
    }
}

try {
    Write-Step "==============================================" Yellow
    Write-Step " E-Commerce User Behavior Analysis - Stop" Yellow
    Write-Step "==============================================" Yellow

    Write-Step "`n[1/4] Checking Docker..." Green
    $dockerReady = $false
    $dockerCli = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCli) {
        Write-Step "Docker CLI not found. Compose services will be skipped." Yellow
    } else {
        cmd /c "docker info >nul 2>nul" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Step "Docker is not running. Compose services will be skipped." Yellow
        } else {
            $dockerReady = $true
            Write-Step "Docker is ready." Green
        }
    }

    Write-Step "`n[2/4] Stopping compose services..." Green
    if ($dockerReady) {
        Push-Location $Root
        try {
            & docker compose down --remove-orphans
            if ($LASTEXITCODE -ne 0) {
                throw "docker compose down failed."
            }
        }
        finally {
            Pop-Location
        }
        Write-Step "Compose services stopped." Green
    } else {
        Write-Step "Skipped compose shutdown because Docker is not available." Yellow
    }

    Write-Step "`n[3/4] Stopping host Chrome CDP and relay..." Green
    Stop-MatchingProcess -Name 'node.exe' -CommandLinePattern '*scripts\cdp_relay.mjs*' -Label 'CDP relay'
    Stop-MatchingProcess -Name 'chrome.exe' -CommandLinePattern "*--user-data-dir=$HostChromeProfileDir*" -Label 'Chrome CDP'

    Write-Step "`n[4/4] Notes" Green
    Write-Step "Compose-managed MySQL is stopped with the project. Data remains in the mysql_data Docker volume." Yellow

    Write-Step "`n==============================================" Yellow
    Write-Step "Stop completed." Green
    Write-Step "==============================================" Yellow
}
catch {
    Write-Step "`nStop failed." Red
    Write-Step $_.Exception.Message Yellow
}
finally {
    Hold-Window
}
