param(
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'

$Green = 'Green'
$Yellow = 'Yellow'
$Red = 'Red'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$MySqlHost = 'localhost'
$MySqlPort = 3306
$MySqlDatabase = 'ecommerce_analysis'
$MySqlUser = 'root'
$MySqlPassword = 'lmh041105666'
$UpgradeWarn = $false

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

function Invoke-CmdQuiet {
    param(
        [string]$Command
    )

    $output = cmd /c $Command 2>$null
    return @{
        ExitCode = $LASTEXITCODE
        Output = @($output)
    }
}

function Invoke-MySqlNative {
    param(
        [string[]]$Arguments,
        [string]$InputText = ''
    )

    $previousPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $MySqlPassword
    try {
        if ($InputText) {
            $InputText | & mysql @Arguments 2>$null
        } else {
            & mysql @Arguments 2>$null
        }
        return @{
            ExitCode = $LASTEXITCODE
            Output = @()
        }
    }
    finally {
        if ($null -eq $previousPwd) {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $previousPwd
        }
    }
}

function Invoke-MySqlQuery {
    param(
        [string]$Sql
    )

    return Invoke-MySqlNative -Arguments @(
        '-h', $MySqlHost,
        '-P', "$MySqlPort",
        "-u$MySqlUser",
        '-Nse', $Sql
    )
}

function Invoke-MySqlFile {
    param(
        [string]$FilePath
    )

    $content = Get-Content -Raw $FilePath
    return Invoke-MySqlNative -Arguments @(
        '-h', $MySqlHost,
        '-P', "$MySqlPort",
        "-u$MySqlUser"
    ) -InputText $content
}

function Test-MySqlReady {
    $result = Invoke-MySqlNative -Arguments @(
        '-h', $MySqlHost,
        '-P', "$MySqlPort",
        "-u$MySqlUser",
        '-e', 'SELECT 1'
    )
    return $result.ExitCode -eq 0
}

try {
    Write-Step "==============================================" $Yellow
    Write-Step " E-Commerce User Behavior Analysis - Start (Windows)" $Yellow
    Write-Step "==============================================" $Yellow

    Write-Step "`n[1/4] Checking local MySQL service..." $Green
    $mysqlCli = Get-Command mysql -ErrorAction SilentlyContinue
    if (-not $mysqlCli) {
        Write-Step "MySQL CLI not found in PATH. Backend can still start, but schema checks will be skipped." $Yellow
        $mysqlReady = $false
    } else {
        $mysqlReady = Test-MySqlReady
        if (-not $mysqlReady) {
            Write-Step "Local MySQL is not reachable at ${MySqlHost}:$MySqlPort." $Red
            Write-Step "Please ensure your MySQL Windows service is running and the password is correct." $Yellow
            throw "MySQL check failed"
        }
        Write-Step "Local MySQL is ready."
    }

    Write-Step "`n[2/4] Starting Redis container if Docker is available..." $Green
    $dockerCli = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCli) {
        Write-Step "Docker CLI not found. Redis container startup skipped." $Yellow
    } else {
        try {
            & docker info *> $null
            if ($LASTEXITCODE -ne 0) {
                Write-Step "Docker is not available for current terminal. Redis container startup skipped." $Yellow
            } else {
                $redisRunning = Invoke-CmdQuiet 'docker ps -q -f "name=redis-ecommerce"'
                if (-not ($redisRunning.Output -join '').Trim()) {
                    $redisExists = Invoke-CmdQuiet 'docker ps -aq -f "name=redis-ecommerce"'
                    if (($redisExists.Output -join '').Trim()) {
                        Write-Step "Starting existing Redis container..."
                        $redisStart = Invoke-CmdQuiet "docker start redis-ecommerce"
                        if ($redisStart.ExitCode -ne 0) {
                            Write-Step "Failed to start existing Redis container. Redis startup skipped." $Yellow
                        }
                    } else {
                        Write-Step "Creating and starting Redis container..."
                        $redisCreate = Invoke-CmdQuiet "docker run -d --name redis-ecommerce -p 6379:6379 redis:7-alpine"
                        if ($redisCreate.ExitCode -ne 0) {
                            Write-Step "Failed to create Redis container. Redis startup skipped." $Yellow
                        }
                    }
                } else {
                    Write-Step "Redis container is already running."
                }
            }
        } catch {
            Write-Step "Docker emitted warnings or is inaccessible. Redis startup skipped." $Yellow
        }
    }

    Write-Step "`n[3/4] Initializing database schema if possible..." $Green
    if ($mysqlReady) {
        $requiredTables = @(
            'sys_user',
            'user_behavior',
            'user_profile',
            'product',
            'category',
            'product_public_mapping',
            'product_public_metric',
            'analysis_cache',
            'sys_log'
        )

        $missingTables = New-Object System.Collections.Generic.List[string]
        foreach ($table in $requiredTables) {
            $query = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$MySqlDatabase' AND table_name='$table'"
            $result = Invoke-MySqlQuery $query
            if ($result.ExitCode -ne 0) {
                throw "Failed to check table '$table'."
            }
            $exists = ($result.Output -join '').Trim()
            if ($exists -ne '1') {
                $missingTables.Add($table)
            }
        }

        if ($missingTables.Count -gt 0) {
            Write-Step ("Missing tables: " + ($missingTables -join ', ')) $Yellow
            Write-Step "Running init.sql..." $Yellow
            $initSqlPath = Join-Path $Root 'backend\src\main\resources\sql\init.sql'
            if (-not (Test-Path $initSqlPath)) {
                throw "init.sql not found: $initSqlPath"
            }
            $initResult = Invoke-MySqlFile $initSqlPath
            if ($initResult.ExitCode -ne 0) {
                throw "init.sql execution failed."
            }
            Write-Step "init.sql executed."
        } else {
            Write-Step "Schema already initialized."
        }

        $upgradeSqlPath = Join-Path $Root 'backend\src\main\resources\sql\upgrade_archive_dataset.sql'
        if (Test-Path $upgradeSqlPath) {
            Write-Step "Applying upgrade_archive_dataset.sql..." $Yellow
            $upgradeResult = Invoke-MySqlFile $upgradeSqlPath
            if ($upgradeResult.ExitCode -ne 0) {
                Write-Step "Warning: upgrade_archive_dataset.sql execution failed. Continuing startup..." $Yellow
                $UpgradeWarn = $true
            }
        }
    } else {
        Write-Step "Skipping schema initialization because MySQL CLI is unavailable." $Yellow
    }

    Write-Step "`n[4/4] Launching backend and frontend in new terminals..." $Green
    $viteCmd = Join-Path $Root 'frontend\node_modules\.bin\vite.cmd'
    if (-not (Test-Path $viteCmd)) {
        Write-Step "Frontend dependencies not found. Running npm install..." $Yellow
        Push-Location (Join-Path $Root 'frontend')
        npm install
        $npmExitCode = $LASTEXITCODE
        Pop-Location
        if ($npmExitCode -ne 0) {
            throw "npm install failed. Please check Node/npm network and retry."
        }
    }

    $backendCommand = @(
        "Set-Location '$Root\backend'"
        "`$env:SPRING_DATASOURCE_URL='jdbc:mysql://${MySqlHost}:$MySqlPort/$MySqlDatabase?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true'"
        "`$env:SPRING_DATASOURCE_USERNAME='$MySqlUser'"
        "`$env:SPRING_DATASOURCE_PASSWORD='$MySqlPassword'"
        'mvn spring-boot:run'
    ) -join '; '

    Start-Process powershell -ArgumentList @(
        '-NoExit',
        '-Command',
        $backendCommand
    )
    Write-Step "Backend started in new window (Port 8080)." $Yellow

    Start-Process powershell -ArgumentList @(
        '-NoExit',
        '-Command',
        "Set-Location '$Root\frontend'; npm run dev"
    )
    Write-Step "Frontend started in new window (Port 3000)." $Yellow

    Write-Step "`n==============================================" $Yellow
    Write-Step "Startup commands sent." $Green
    Write-Step "Local MySQL: ${MySqlHost}:$MySqlPort/$MySqlDatabase" $Green
    Write-Step "Frontend: http://localhost:3000" $Green
    Write-Step "Swagger:  http://localhost:8080/api/swagger-ui.html" $Green
    if ($UpgradeWarn) {
        Write-Step "Warning: DB upgrade script had errors. Please check DB logs." $Yellow
    }
    Write-Step "==============================================" $Yellow
}
catch {
    Write-Step "`nScript failed." $Red
    Write-Step $_.Exception.Message $Yellow
}
finally {
    Hold-Window
}
