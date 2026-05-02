@echo off
setlocal enabledelayedexpansion

set "PAUSE_ON_EXIT=1"
set "BUILD_MODE=0"
for %%A in (%*) do (
  if /I "%%~A"=="--no-pause" set "PAUSE_ON_EXIT=0"
  if /I "%%~A"=="--build" set "BUILD_MODE=1"
)

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "ENV_FILE=%ROOT%\.env"
set "ENV_EXAMPLE=%ROOT%\.env.example"
set "RUNTIME_DIR=%ROOT%\runtime\browser-profile"
set "HOST_CHROME_PROFILE=C:\chrome-jd-profile"
set "CDP_RELAY_SCRIPT=%ROOT%\scripts\cdp_relay.mjs"
set "DOCKER_READY=0"

echo ==============================================
echo  E-Commerce User Behavior Analysis - Start
echo ==============================================

echo.
echo [1/6] Checking Docker...
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker CLI not found. Please install Docker Desktop first.
  goto fail
)

for /L %%i in (1,1,12) do (
  docker info >nul 2>nul
  if not errorlevel 1 (
    set "DOCKER_READY=1"
    goto docker_ready
  )
  if %%i LSS 12 (
    echo Docker engine is still starting, retrying in 5 seconds... (%%i/12)
    timeout /t 5 /nobreak >nul
  )
)

:docker_ready
if not "%DOCKER_READY%"=="1" (
  echo Docker is not running. Please start Docker Desktop and retry.
  goto fail
)
echo Docker is ready.

echo.
echo [2/6] Preparing environment files...
if not exist "%ENV_FILE%" (
  if not exist "%ENV_EXAMPLE%" (
    echo .env.example not found.
    goto fail
  )
  copy /Y "%ENV_EXAMPLE%" "%ENV_FILE%" >nul
  echo Created .env from .env.example
) else (
  echo .env already exists.
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='%ENV_FILE%'; $updates=@{PUBLIC_TASK_WORKER_URL='http://public-task-worker:8090'; PUBLIC_TASK_CDP_URL='http://host.docker.internal:9223'}; $lines=@(Get-Content -LiteralPath $p); foreach($k in $updates.Keys){ $pattern='^'+[regex]::Escape($k)+'='; $replacement=$k+'='+$updates[$k]; if($lines -match $pattern){ $lines=$lines -replace ($pattern+'.*'), $replacement } else { $lines += $replacement } }; Set-Content -LiteralPath $p -Value $lines"
echo Public task worker and CDP relay addresses are configured in .env.

echo.
echo [3/6] Preparing runtime directories...
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
echo Runtime directory ready: %RUNTIME_DIR%
echo MySQL is managed by docker compose by default. If you configured host MySQL in .env, make sure it is running.

echo.
echo [4/6] Starting services...
if "%BUILD_MODE%"=="1" (
  echo Build mode enabled: Docker images will be rebuilt before startup.
) else (
  echo Fast start mode: reusing existing images. Use --build to rebuild when dependencies change.
)
pushd "%ROOT%"
if "%BUILD_MODE%"=="1" (
  docker compose up -d --build
) else (
  docker compose up -d
)
if errorlevel 1 (
  popd
  echo docker compose up failed.
  goto fail
)
popd
echo Docker services started.

echo.
echo [5/6] Starting host Chrome CDP and relay...
if not exist "%HOST_CHROME_PROFILE%" mkdir "%HOST_CHROME_PROFILE%"

netstat -ano | findstr /R /C:":9222 .*LISTENING" >nul 2>nul
if not errorlevel 1 (
  echo Chrome CDP port 9222 is already listening.
) else (
  set "CHROME_EXE="
  if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "CHROME_EXE=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
  if not defined CHROME_EXE if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" set "CHROME_EXE=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
  if not defined CHROME_EXE if exist "%LocalAppData%\Google\Chrome\Application\chrome.exe" set "CHROME_EXE=%LocalAppData%\Google\Chrome\Application\chrome.exe"
  if not defined CHROME_EXE (
    echo Chrome was not found. Candidate recall via JD browser reuse needs Chrome listening on port 9222.
  ) else (
    start "" "!CHROME_EXE!" --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 --user-data-dir="%HOST_CHROME_PROFILE%" https://www.jd.com
    echo Started Chrome CDP on port 9222 with profile: %HOST_CHROME_PROFILE%
  )
)

timeout /t 2 /nobreak >nul

netstat -ano | findstr /R /C:":9223 .*LISTENING" >nul 2>nul
if not errorlevel 1 (
  echo CDP relay port 9223 is already listening.
) else if not exist "%CDP_RELAY_SCRIPT%" (
  echo CDP relay script not found: %CDP_RELAY_SCRIPT%
) else (
  where node >nul 2>nul
  if errorlevel 1 (
    echo Node.js not found. Cannot start CDP relay on port 9223.
  ) else (
    start "CDP Relay" /min node "%CDP_RELAY_SCRIPT%"
    echo Started CDP relay: http://host.docker.internal:9223 -^> 127.0.0.1:9222
  )
)

echo.
echo [6/6] Checking service status...
pushd "%ROOT%"
docker compose ps
popd

echo.
echo ==============================================
echo Startup completed.
echo Frontend: http://localhost
echo Backend:  http://localhost:8080/api
echo Swagger:  http://localhost:8080/api/swagger-ui.html
echo Worker:   http://localhost:8090/health
echo CDP:      http://host.docker.internal:9223/json/version
echo Please log in to JD in the Chrome window opened by this script before candidate recall.
if "%BUILD_MODE%"=="1" (
  echo Rebuild completed. Dependency downloads may take a while when images change.
) else (
  echo Started without rebuilding images. Re-run with --build if dependencies or Dockerfiles changed.
)
echo ==============================================
goto success

:fail
echo.
echo Startup failed.
if "%PAUSE_ON_EXIT%"=="1" goto hold
exit /b 1

:success
if "%PAUSE_ON_EXIT%"=="1" goto hold
exit /b 0

:hold
echo.
echo Window is being kept open for inspection.
echo Type exit or close this window when you are done.
cmd /k
