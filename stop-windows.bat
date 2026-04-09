@echo off
setlocal enabledelayedexpansion
set "PAUSE_ON_EXIT=1"
if /I "%~1"=="--no-pause" set "PAUSE_ON_EXIT=0"
set "NEED_ADMIN=0"
set "HAS_FAILURE=0"

echo ==============================================
echo  E-Commerce User Behavior Analysis - Stop (Windows)
echo ==============================================

echo.
echo [1/3] Stopping backend (Port 8080)...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do (
  taskkill /PID %%p /F >nul 2>nul
  if not errorlevel 1 (
    echo Backend stopped (PID: %%p).
  ) else (
    echo Failed to stop backend PID %%p. Try running this script as Administrator.
    set "HAS_FAILURE=1"
    set "NEED_ADMIN=1"
  )
)

echo.
echo [2/3] Stopping frontend (Port 3000)...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":3000 .*LISTENING"') do (
  taskkill /PID %%p /F >nul 2>nul
  if not errorlevel 1 (
    echo Frontend stopped (PID: %%p).
  ) else (
    echo Failed to stop frontend PID %%p. Try running this script as Administrator.
    set "HAS_FAILURE=1"
    set "NEED_ADMIN=1"
  )
)

echo.
echo [3/3] Stopping Redis container if it is running...
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker CLI not found. Redis stop skipped.
) else (
  for /f %%i in ('docker ps -q -f "name=redis-ecommerce"') do set "REDIS_RUNNING=%%i"
  if defined REDIS_RUNNING (
    echo Stopping Redis container...
    docker stop redis-ecommerce >nul
    if errorlevel 1 (
      echo Failed to stop Redis container.
      set "HAS_FAILURE=1"
    )
  ) else (
    echo Redis container is not running.
  )
)

echo.
echo Note: local MySQL Windows service is not managed by this script.

echo.
echo ==============================================
if "%HAS_FAILURE%"=="1" (
  echo Stop finished with errors.
) else (
  echo All managed services stopped.
)
echo ==============================================

if "%NEED_ADMIN%"=="1" (
  echo.
  echo Tip: Right-click stop-windows.bat and choose "Run as administrator".
)

if "%PAUSE_ON_EXIT%"=="1" goto hold
exit /b 0

:hold
echo.
echo Window is being kept open for inspection.
echo Type ^`exit^` or close this window when you are done.
cmd /k
