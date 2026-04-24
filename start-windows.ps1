param(
    [switch]$NoPause,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $Root '.env'
$EnvExampleFile = Join-Path $Root '.env.example'
$RuntimeProfileDir = Join-Path $Root 'runtime\browser-profile'
$HostChromeProfileDir = 'C:\chrome-jd-profile'
$CdpRelayScript = Join-Path $Root 'scripts\cdp_relay.mjs'

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

function Test-DockerReady {
    param(
        [int]$MaxAttempts = 12,
        [int]$DelaySeconds = 5
    )

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        cmd /c "docker info >nul 2>nul" | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return $true
        }
        if ($attempt -lt $MaxAttempts) {
            Write-Step "Docker engine is still starting, retrying in $DelaySeconds seconds... ($attempt/$MaxAttempts)" Yellow
            Start-Sleep -Seconds $DelaySeconds
        }
    }
    return $false
}

function Set-EnvFileValue {
    param(
        [string]$Path,
        [string]$Key,
        [string]$Value
    )

    $lines = @(Get-Content -LiteralPath $Path)
    $pattern = '^' + [regex]::Escape($Key) + '='
    $replacement = "$Key=$Value"
    $updated = $false
    $lines = $lines | ForEach-Object {
        if ($_ -match $pattern) {
            $updated = $true
            $replacement
        } else {
            $_
        }
    }
    if (-not $updated) {
        $lines += $replacement
    }
    Set-Content -LiteralPath $Path -Value $lines
}

function Test-TcpPortListening {
    param([int]$Port)

    $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return [bool]$connections
}

function Find-ChromePath {
    $candidates = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LocalAppData\Google\Chrome\Application\chrome.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }
    return $null
}

function Start-CdpServices {
    Write-Step "`n[5/6] Starting host Chrome CDP and relay..." Green

    if (-not (Test-Path $HostChromeProfileDir)) {
        New-Item -ItemType Directory -Path $HostChromeProfileDir -Force | Out-Null
    }

    if (Test-TcpPortListening -Port 9222) {
        Write-Step "Chrome CDP port 9222 is already listening." Green
    } else {
        $chromePath = Find-ChromePath
        if (-not $chromePath) {
            Write-Step "Chrome was not found. Candidate recall via JD browser reuse needs Chrome listening on port 9222." Yellow
        } else {
            Start-Process -FilePath $chromePath -ArgumentList @(
                '--remote-debugging-address=0.0.0.0',
                '--remote-debugging-port=9222',
                "--user-data-dir=$HostChromeProfileDir",
                'https://www.jd.com'
            )
            Write-Step "Started Chrome CDP on port 9222 with profile: $HostChromeProfileDir" Green
        }
    }

    Start-Sleep -Seconds 2

    if (Test-TcpPortListening -Port 9223) {
        Write-Step "CDP relay port 9223 is already listening." Green
    } elseif (-not (Test-Path $CdpRelayScript)) {
        Write-Step "CDP relay script not found: $CdpRelayScript" Yellow
    } else {
        $nodeCli = Get-Command node -ErrorAction SilentlyContinue
        if (-not $nodeCli) {
            Write-Step "Node.js not found. Cannot start CDP relay on port 9223." Yellow
        } else {
            Start-Process -FilePath $nodeCli.Source -ArgumentList @($CdpRelayScript) -WorkingDirectory $Root -WindowStyle Minimized
            Write-Step "Started CDP relay: http://host.docker.internal:9223 -> 127.0.0.1:9222" Green
        }
    }
}

try {
    Write-Step "==============================================" Yellow
    Write-Step " E-Commerce User Behavior Analysis - Start" Yellow
    Write-Step "==============================================" Yellow

    Write-Step "`n[1/6] Checking Docker..." Green
    $dockerCli = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCli) {
        throw "Docker CLI not found. Please install Docker Desktop first."
    }
    if (-not (Test-DockerReady)) {
        throw "Docker is not running. Please start Docker Desktop and retry."
    }
    Write-Step "Docker is ready." Green

    Write-Step "`n[2/6] Preparing environment files..." Green
    if (-not (Test-Path $EnvFile)) {
        if (-not (Test-Path $EnvExampleFile)) {
            throw ".env.example not found."
        }
        Copy-Item -LiteralPath $EnvExampleFile -Destination $EnvFile
        Write-Step "Created .env from .env.example" Yellow
    } else {
        Write-Step ".env already exists." Green
    }
    Set-EnvFileValue -Path $EnvFile -Key 'PUBLIC_TASK_WORKER_URL' -Value 'http://public-task-worker:8090'
    Set-EnvFileValue -Path $EnvFile -Key 'PUBLIC_TASK_CDP_URL' -Value 'http://host.docker.internal:9223'
    Write-Step "Public task worker and CDP relay addresses are configured in .env." Green

    Write-Step "`n[3/6] Preparing runtime directories..." Green
    if (-not (Test-Path $RuntimeProfileDir)) {
        New-Item -ItemType Directory -Path $RuntimeProfileDir -Force | Out-Null
    }
    Write-Step "Runtime directory ready: $RuntimeProfileDir" Green
    Write-Step "Using local MySQL from .env. Please make sure your host MySQL service is already running." Yellow

    $composeArgs = @('compose', 'up', '-d')
    if ($Build) {
        $composeArgs += '--build'
    }

    Write-Step "`n[4/6] Starting services..." Green
    if ($Build) {
        Write-Step "Build mode enabled: Docker images will be rebuilt before startup." Yellow
    } else {
        Write-Step "Fast start mode: reusing existing images. Use -Build to rebuild when dependencies change." Yellow
    }
    Push-Location $Root
    try {
        & docker @composeArgs
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed."
        }
    }
    finally {
        Pop-Location
    }
    Write-Step "Docker services started." Green

    Start-CdpServices

    Write-Step "`n[6/6] Checking service status..." Green
    Push-Location $Root
    try {
        & docker compose ps
    }
    finally {
        Pop-Location
    }

    Write-Step "`n==============================================" Yellow
    Write-Step "Startup completed." Green
    Write-Step "Frontend: http://localhost" Green
    Write-Step "Backend:  http://localhost:8080/api" Green
    Write-Step "Swagger:  http://localhost:8080/api/swagger-ui.html" Green
    Write-Step "Worker:   http://localhost:8090/health" Green
    Write-Step "CDP:      http://host.docker.internal:9223/json/version" Green
    Write-Step "Please log in to JD in the Chrome window opened by this script before candidate recall." Yellow
    if ($Build) {
        Write-Step "Rebuild completed. Dependency downloads may take a while when images change." Yellow
    } else {
        Write-Step "Started without rebuilding images. Re-run with -Build if dependencies or Dockerfiles changed." Yellow
    }
    Write-Step "==============================================" Yellow
}
catch {
    Write-Step "`nStartup failed." Red
    Write-Step $_.Exception.Message Yellow
}
finally {
    Hold-Window
}
