@echo off
setlocal enabledelayedexpansion
set "PAUSE_ON_EXIT=1"
if /I "%~1"=="--no-pause" set "PAUSE_ON_EXIT=0"
set "UPGRADE_WARN=0"
set "MYSQL_READY=0"
set "MYSQL_CLIENT_FOUND=0"

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "MYSQL_HOST=localhost"
set "MYSQL_PORT=3306"
set "MYSQL_DB=ecommerce_analysis"
set "MYSQL_USER=root"
set "MYSQL_PASSWORD=lmh041105666"

echo ==============================================
echo  E-Commerce User Behavior Analysis - Start (Windows)
echo ==============================================

echo.
echo [1/4] Checking local MySQL service...
where mysql >nul 2>nul
if not errorlevel 1 set "MYSQL_CLIENT_FOUND=1"

if "%MYSQL_CLIENT_FOUND%"=="1" (
  mysql -h %MYSQL_HOST% -P %MYSQL_PORT% -u%MYSQL_USER% -p%MYSQL_PASSWORD% -e "SELECT 1" >nul 2>nul
  if errorlevel 1 (
    echo Local MySQL is not reachable at %MYSQL_HOST%:%MYSQL_PORT%.
    echo Please ensure your MySQL Windows service is running and the password is correct.
    goto fail
  )
  set "MYSQL_READY=1"
  echo Local MySQL is ready.
) else (
  echo MySQL CLI not found in PATH. Will skip schema checks, but backend will still use local MySQL.
)

echo.
echo [2/4] Starting Redis container if Docker is available...
set "DOCKER_READY=0"
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker CLI not found. Redis container startup skipped.
) else (
  docker info >nul 2>nul
  if errorlevel 1 (
    echo Docker is not available for current terminal. Redis container startup skipped.
  ) else (
    set "DOCKER_READY=1"
    for /f %%i in ('docker ps -q -f "name=redis-ecommerce"') do set "REDIS_RUNNING=%%i"
    if not defined REDIS_RUNNING (
      for /f %%i in ('docker ps -aq -f "name=redis-ecommerce"') do set "REDIS_EXISTS=%%i"
      if defined REDIS_EXISTS (
        echo Starting existing Redis container...
        docker start redis-ecommerce >nul
        if errorlevel 1 (
          echo Failed to start existing Redis container.
          goto fail
        )
      ) else (
        echo Creating and starting Redis container...
        docker run -d --name redis-ecommerce -p 6379:6379 redis:7-alpine >nul
        if errorlevel 1 (
          echo Failed to create Redis container. Port 6379 may already be in use.
          goto fail
        )
      )
    ) else (
      echo Redis container is already running.
    )
  )
)

echo.
echo [3/4] Initializing database schema if possible...
if "%MYSQL_READY%"=="1" (
  set "MISSING_TABLES="
  for %%T in (sys_user user_behavior user_profile product category product_public_mapping product_public_metric analysis_cache sys_log) do (
    set "TABLE_EXISTS="
    for /f %%i in ('mysql -h %MYSQL_HOST% -P %MYSQL_PORT% -u%MYSQL_USER% -p%MYSQL_PASSWORD% -Nse "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%MYSQL_DB%' AND table_name='%%T'" 2^>nul') do set "TABLE_EXISTS=%%i"
    if not defined TABLE_EXISTS (
      echo Failed to check table %%T.
      goto fail
    )
    if not "!TABLE_EXISTS!"=="1" (
      if defined MISSING_TABLES (
        set "MISSING_TABLES=!MISSING_TABLES!, %%T"
      ) else (
        set "MISSING_TABLES=%%T"
      )
    )
  )

  if defined MISSING_TABLES (
    echo Missing tables: %MISSING_TABLES%
    echo Running init.sql...
    if not exist "%ROOT%\backend\src\main\resources\sql\init.sql" (
      echo init.sql not found.
      goto fail
    )
    mysql -h %MYSQL_HOST% -P %MYSQL_PORT% -u%MYSQL_USER% -p%MYSQL_PASSWORD% < "%ROOT%\backend\src\main\resources\sql\init.sql"
    if errorlevel 1 (
      echo init.sql execution failed.
      goto fail
    )
    echo init.sql executed.
  ) else (
    echo Schema already initialized.
  )

  if exist "%ROOT%\backend\src\main\resources\sql\upgrade_archive_dataset.sql" (
    echo Applying upgrade_archive_dataset.sql...
    mysql -h %MYSQL_HOST% -P %MYSQL_PORT% -u%MYSQL_USER% -p%MYSQL_PASSWORD% < "%ROOT%\backend\src\main\resources\sql\upgrade_archive_dataset.sql"
    if errorlevel 1 (
      echo Warning: upgrade_archive_dataset.sql execution failed. Continuing startup...
      set "UPGRADE_WARN=1"
    )
  )
) else (
  echo Skipping schema initialization because MySQL CLI is unavailable.
)

echo.
echo [4/4] Launching backend and frontend in new terminals...
if not exist "%ROOT%\frontend\node_modules\.bin\vite.cmd" (
  echo Frontend dependencies not found. Running npm install...
  pushd "%ROOT%\frontend"
  call npm install
  if errorlevel 1 (
    popd
    echo npm install failed. Please check Node/npm network and retry.
    goto fail
  )
  popd
)

start "backend" cmd /k "cd /d ""%ROOT%\backend"" && set SPRING_DATASOURCE_URL=jdbc:mysql://%MYSQL_HOST%:%MYSQL_PORT%/%MYSQL_DB%?useUnicode=true^&characterEncoding=utf-8^&serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true^&rewriteBatchedStatements=true&& set SPRING_DATASOURCE_USERNAME=%MYSQL_USER%&& set SPRING_DATASOURCE_PASSWORD=%MYSQL_PASSWORD%&& mvn spring-boot:run"
echo Backend started in new window (Port 8080).

start "frontend" cmd /k "cd /d ""%ROOT%\frontend"" && npm run dev"
echo Frontend started in new window (Port 3000).

echo.
echo ==============================================
echo Startup commands sent.
echo Local MySQL: %MYSQL_HOST%:%MYSQL_PORT%/%MYSQL_DB%
echo Frontend: http://localhost:3000
echo Swagger:  http://localhost:8080/api/swagger-ui.html
if "%UPGRADE_WARN%"=="1" (
  echo Warning: DB upgrade script had errors. Please check DB logs.
)
echo ==============================================

goto success

:fail
echo.
echo Script failed.
if "%PAUSE_ON_EXIT%"=="1" goto hold
exit /b 1

:success
if "%PAUSE_ON_EXIT%"=="1" goto hold
exit /b 0

:hold
echo.
echo Window is being kept open for inspection.
echo Type ^`exit^` or close this window when you are done.
cmd /k
