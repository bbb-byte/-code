param(
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

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

try {
    Write-Step "==============================================" Yellow
    Write-Step " E-Commerce User Behavior Analysis - Stop" Yellow
    Write-Step "==============================================" Yellow

    Write-Step "`n[1/3] Checking Docker..." Green
    $dockerCli = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCli) {
        throw "Docker CLI not found. Please install Docker Desktop first."
    }
    cmd /c "docker info >nul 2>nul" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not running. Please start Docker Desktop and retry."
    }
    Write-Step "Docker is ready." Green

    Write-Step "`n[2/3] Stopping compose services..." Green
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

    Write-Step "`n[3/3] Notes" Green
    Write-Step "Local MySQL service is not managed by this script and was left untouched." Yellow

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
