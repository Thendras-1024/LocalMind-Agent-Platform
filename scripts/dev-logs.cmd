@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"
set "COMPOSE_FILE=%PROJECT_ROOT%\ops\docker-compose.dev.yml"

where docker >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Docker command not found.
  exit /b 1
)

docker info >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Docker Desktop is not running.
  exit /b 1
)

if not exist "%COMPOSE_FILE%" (
  echo [ERROR] Compose file not found: %COMPOSE_FILE%
  exit /b 1
)

docker compose -f "%COMPOSE_FILE%" logs -f redis kafka
