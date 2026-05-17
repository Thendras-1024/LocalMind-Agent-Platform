@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_ROOT=%%~fI"
set "COMPOSE_FILE=%PROJECT_ROOT%\ops\docker-compose.kafka.yml"

where docker >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Docker command not found. Please install Docker Desktop first.
  exit /b 1
)

docker info >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Docker Desktop is not running. Please start Docker Desktop and try again.
  exit /b 1
)

if not exist "%COMPOSE_FILE%" (
  echo [ERROR] Compose file not found: %COMPOSE_FILE%
  exit /b 1
)

echo [INFO] Starting Kafka with Docker Compose...
docker compose -f "%COMPOSE_FILE%" up -d
if errorlevel 1 exit /b 1

echo [INFO] Kafka is starting. Check logs with:
echo        %PROJECT_ROOT%\scripts\kafka-logs.cmd
echo [INFO] Spring Boot can keep using localhost:9092
exit /b 0
