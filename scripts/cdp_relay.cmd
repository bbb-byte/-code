@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PY_SCRIPT=%SCRIPT_DIR%cdp_relay.py"
set "NODE_SCRIPT=%SCRIPT_DIR%cdp_relay.mjs"

where node >nul 2>nul
if %errorlevel%==0 (
  node "%NODE_SCRIPT%"
  goto :eof
)

where py >nul 2>nul
if %errorlevel%==0 (
  py -3 "%PY_SCRIPT%"
  goto :eof
)

where python >nul 2>nul
if %errorlevel%==0 (
  python "%PY_SCRIPT%"
  goto :eof
)

echo Neither Node.js nor Python was found. Please install Node.js or Python.
exit /b 1
