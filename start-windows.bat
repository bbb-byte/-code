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
set "DOCKER_READY=0"

echo ==============================================
echo  E-Commerce User Behavior Analysis - Start
echo ==============================================

echo.
echo [1/5] Checking Docker...
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
echo [2/5] Preparing environment files...
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

echo.
echo [3/5] Preparing runtime directories...
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%"
echo Runtime directory ready: %RUNTIME_DIR%
echo Using local MySQL from .env. Please make sure your host MySQL service is already running.

echo.
echo [4/5] Starting services...
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
echo [5/5] Checking service status...
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
