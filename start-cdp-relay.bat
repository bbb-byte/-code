@echo off
setlocal
set "NODE_SCRIPT=%~dp0scripts\cdp_relay.mjs"
set "PY_SCRIPT=%~dp0scripts\cdp_relay.py"

where node >nul 2>nul
if %errorlevel%==0 (
  echo Starting CDP relay (Node.js)...
  node "%NODE_SCRIPT%"
  goto :eof
)

echo Node.js not found, falling back to Python relay...

if exist "C:\Users\86186\anaconda3\python.exe" (
  "C:\Users\86186\anaconda3\python.exe" "%PY_SCRIPT%"
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

echo Neither Node.js nor Python was found. Please install Node.js.
pause
