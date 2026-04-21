@echo off
setlocal enabledelayedexpansion

set "PAUSE_ON_EXIT=1"
if /I "%~1"=="--no-pause" set "PAUSE_ON_EXIT=0"

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

echo ==============================================
echo  E-Commerce User Behavior Analysis - Stop
echo ==============================================

echo.
echo [1/3] Checking Docker...
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker CLI not found. Please install Docker Desktop first.
  goto fail
)

docker info >nul 2>nul
if errorlevel 1 (
  echo Docker is not running. Please start Docker Desktop and retry.
  goto fail
)
echo Docker is ready.

echo.
echo [2/3] Stopping compose services...
pushd "%ROOT%"
docker compose down --remove-orphans
if errorlevel 1 (
  popd
  echo docker compose down failed.
  goto fail
)
popd
echo Compose services stopped.

echo.
echo [3/3] Notes
echo Local MySQL service is not managed by this script and was left untouched.

echo.
echo ==============================================
echo Stop completed.
echo ==============================================
goto success

:fail
echo.
echo Stop failed.
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
