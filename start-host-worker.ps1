param(
    [switch]$NoPause,
    [int]$Port = 8090
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonCmd = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }
$PythonArgs = if ($PythonCmd -eq 'py') { @('-3') } else { @() }

$Env:PUBLIC_TASK_WORKSPACE_ROOT = $Root
$Env:PUBLIC_TASK_WORKER_PORT = "$Port"
if (-not $Env:PUBLIC_TASK_PYTHON) {
    $Env:PUBLIC_TASK_PYTHON = 'python'
}
if (-not $Env:PUBLIC_TASK_BROWSER_PROFILE_DIR) {
    $Env:PUBLIC_TASK_BROWSER_PROFILE_DIR = (Join-Path $Root 'runtime\browser-profile')
}
if (-not $Env:PUBLIC_TASK_BROWSER_PATH) {
    $chromeCandidates = @()
    if ($Env:ProgramFiles) {
        $chromeCandidates += (Join-Path $Env:ProgramFiles 'Google\Chrome\Application\chrome.exe')
    }
    if (${Env:ProgramFiles(x86)}) {
        $chromeCandidates += (Join-Path ${Env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe')
    }
    if ($Env:LocalAppData) {
        $chromeCandidates += (Join-Path $Env:LocalAppData 'Google\Chrome\Application\chrome.exe')
    }
    $chromeCandidates = $chromeCandidates | Where-Object { $_ -and (Test-Path $_) }
    if ($chromeCandidates.Count -gt 0) {
        $Env:PUBLIC_TASK_BROWSER_PATH = $chromeCandidates[0]
    }
}

Write-Host "Starting host public-task worker..." -ForegroundColor Green
Write-Host "Workspace: $Env:PUBLIC_TASK_WORKSPACE_ROOT" -ForegroundColor Yellow
Write-Host "Python:    $Env:PUBLIC_TASK_PYTHON" -ForegroundColor Yellow
Write-Host "Browser:   $Env:PUBLIC_TASK_BROWSER_PATH" -ForegroundColor Yellow
Write-Host "Profile:   $Env:PUBLIC_TASK_BROWSER_PROFILE_DIR" -ForegroundColor Yellow
Write-Host "Port:      $Port" -ForegroundColor Yellow

Push-Location $Root
try {
    & $PythonCmd @PythonArgs worker\server.py
}
finally {
    Pop-Location
    if (-not $NoPause) {
        Read-Host "`nPress Enter to close"
    }
}
