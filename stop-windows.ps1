param(
    [switch]$NoPause
)

$ErrorActionPreference = 'SilentlyContinue'

$Green = 'Green'
$Yellow = 'Yellow'
$Red = 'Red'
$NeedAdmin = $false
$HasFailure = $false

function Write-Step {
    param(
        [string]$Message,
        [string]$Color = 'White'
    )
    Write-Host $Message -ForegroundColor $Color
}

function Stop-PortProcess {
    param(
        [int]$Port,
        [string]$ServiceName
    )

    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $connections) {
        Write-Step "No $ServiceName service found on port $Port."
        return
    }

    $pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        try {
            Stop-Process -Id $procId -Force -ErrorAction Stop
            Write-Step "$ServiceName stopped (PID: $procId)."
        } catch {
            Write-Step "Failed to stop $ServiceName (PID: $procId). Try Administrator mode." $Red
            $script:NeedAdmin = $true
            $script:HasFailure = $true
        }
    }
}

Write-Step "==============================================" $Yellow
Write-Step " E-Commerce User Behavior Analysis - Stop (Windows)" $Yellow
Write-Step "==============================================" $Yellow

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Step "Warning: not running as Administrator. Some processes may not be stoppable." $Yellow
}

Write-Step "`n[1/3] Stopping backend (Port 8080)..." $Green
Stop-PortProcess -Port 8080 -ServiceName "Backend"

Write-Step "`n[2/3] Stopping frontend (Port 3000)..." $Green
Stop-PortProcess -Port 3000 -ServiceName "Frontend"

Write-Step "`n[3/3] Stopping Redis container if it is running..." $Green
$dockerCli = Get-Command docker -ErrorAction SilentlyContinue
if (-not $dockerCli) {
    Write-Step "Docker CLI not found. Redis stop skipped." $Yellow
} else {
    $redisRunning = docker ps -q -f "name=redis-ecommerce"
    if ($redisRunning) {
        Write-Step "Stopping Redis container..."
        docker stop redis-ecommerce | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Step "Failed to stop Redis container." $Red
            $HasFailure = $true
        }
    } else {
        Write-Step "Redis container is not running."
    }
}

Write-Step "`nNote: local MySQL Windows service is not managed by this script." $Yellow

Write-Step "`n==============================================" $Yellow
if ($HasFailure) {
    Write-Step "Stop finished with errors." $Red
} else {
    Write-Step "All managed services stopped." $Green
}
Write-Step "==============================================" $Yellow

if ($NeedAdmin) {
    Write-Step "Tip: Run stop-windows.ps1 as Administrator." $Yellow
}

if (-not $NoPause) {
    Read-Host "`nPress Enter to close"
}
