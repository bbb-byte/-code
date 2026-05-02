@echo off
setlocal enabledelayedexpansion

set "PAUSE_ON_EXIT=1"
if /I "%~1"=="--no-pause" set "PAUSE_ON_EXIT=0"

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "HOST_CHROME_PROFILE=C:\chrome-jd-profile"
set "DOCKER_READY=0"

echo ==============================================
echo  E-Commerce User Behavior Analysis - Stop
echo ==============================================

echo.
echo [1/4] Checking Docker...
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker CLI not found. Compose services will be skipped.
) else (
  docker info >nul 2>nul
  if errorlevel 1 (
    echo Docker is not running. Compose services will be skipped.
  ) else (
    set "DOCKER_READY=1"
    echo Docker is ready.
  )
)

echo.
echo [2/4] Stopping compose services...
if "%DOCKER_READY%"=="1" (
  pushd "%ROOT%"
  docker compose down --remove-orphans
  if errorlevel 1 (
    popd
    echo docker compose down failed.
    goto fail
  )
  popd
  echo Compose services stopped.
) else (
  echo Skipped compose shutdown because Docker is not available.
)

echo.
echo [3/4] Stopping host Chrome CDP and relay...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -and $_.CommandLine -like '*scripts\cdp_relay.mjs*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('Stopped CDP relay process ' + $_.ProcessId) }"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'chrome.exe' -and $_.CommandLine -like '*--user-data-dir=%HOST_CHROME_PROFILE%*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('Stopped Chrome CDP process ' + $_.ProcessId) }"

echo.
echo [4/4] Notes
echo Compose-managed MySQL is stopped with the project. Data remains in the mysql_data Docker volume.

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
