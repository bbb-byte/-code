param(
    [switch]$NoPause,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $Root '.env'
$EnvExampleFile = Join-Path $Root '.env.example'
$RuntimeProfileDir = Join-Path $Root 'runtime\browser-profile'

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

try {
    Write-Step "==============================================" Yellow
    Write-Step " E-Commerce User Behavior Analysis - Start" Yellow
    Write-Step "==============================================" Yellow

    Write-Step "`n[1/5] Checking Docker..." Green
    $dockerCli = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCli) {
        throw "Docker CLI not found. Please install Docker Desktop first."
    }
    if (-not (Test-DockerReady)) {
        throw "Docker is not running. Please start Docker Desktop and retry."
    }
    Write-Step "Docker is ready." Green

    Write-Step "`n[2/5] Preparing environment files..." Green
    if (-not (Test-Path $EnvFile)) {
        if (-not (Test-Path $EnvExampleFile)) {
            throw ".env.example not found."
        }
        Copy-Item -LiteralPath $EnvExampleFile -Destination $EnvFile
        Write-Step "Created .env from .env.example" Yellow
    } else {
        Write-Step ".env already exists." Green
    }

    Write-Step "`n[3/5] Preparing runtime directories..." Green
    if (-not (Test-Path $RuntimeProfileDir)) {
        New-Item -ItemType Directory -Path $RuntimeProfileDir -Force | Out-Null
    }
    Write-Step "Runtime directory ready: $RuntimeProfileDir" Green
    Write-Step "Using local MySQL from .env. Please make sure your host MySQL service is already running." Yellow

    $composeArgs = @('compose', 'up', '-d')
    if ($Build) {
        $composeArgs += '--build'
    }

    Write-Step "`n[4/5] Starting services..." Green
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

    Write-Step "`n[5/5] Checking service status..." Green
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
