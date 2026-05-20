# ─────────────────────────────────────────────────────────────────────────────
# deploy.ps1 — Windows PowerShell wrapper around the same docker compose flow
# as deploy.sh. Use this on a Windows host without WSL/Git Bash.
#
# Usage:
#   .\deploy.ps1            # build + start
#   .\deploy.ps1 -Logs      # build + start, then tail logs
#   .\deploy.ps1 -Clean     # wipe DB volume first, then build + start
#   .\deploy.ps1 -Down      # stop everything (volume preserved)
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()]
param(
    [switch]$Logs,
    [switch]$Clean,
    [switch]$Down,
    [switch]$AwsPush,
    [switch]$AzurePush
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Log($msg)  { Write-Host "==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "OK  $msg"  -ForegroundColor Green }
function Warn($msg) { Write-Host "!   $msg"  -ForegroundColor Yellow }
function Die($msg)  { Write-Host "X   $msg"  -ForegroundColor Red; exit 1 }

# ── Prerequisite checks ─────────────────────────────────────────────────────
Log "Checking prerequisites"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Die "docker not found. Install Docker Desktop."
}
# `docker compose` is the v2 subcommand; v1 standalone is deprecated.
docker compose version | Out-Null
if ($LASTEXITCODE -ne 0) { Die "docker compose v2 not found. Update Docker Desktop." }
docker info | Out-Null
if ($LASTEXITCODE -ne 0) { Die "Docker daemon not reachable. Is Docker Desktop running?" }
Ok "docker + compose available"

# ── Tear-down short-circuit ─────────────────────────────────────────────────
if ($Down) {
    Log "Stopping stack (volume preserved)"
    docker compose down
    Ok "Stack stopped. Volume 'securecapita-mysql-data' kept."
    return
}

# ── Helper: load .env.cloud and verify named vars are set ───────────────────
function Import-CloudEnv {
    param([string[]]$Required)
    if (-not (Test-Path .env.cloud)) {
        Die ".env.cloud not found. Copy .env.cloud.example to .env.cloud and fill in your registry details."
    }
    Get-Content .env.cloud | ForEach-Object {
        if ($_ -match '^\s*#') { return }
        if ($_ -match '^\s*$') { return }
        if ($_ -match '^([^=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            Set-Item -Path "env:$name" -Value $value
        }
    }
    foreach ($v in $Required) {
        $val = [Environment]::GetEnvironmentVariable($v)
        if ([string]::IsNullOrWhiteSpace($val)) {
            Die "Missing required env var '$v' in .env.cloud"
        }
    }
}

# ── AWS push ────────────────────────────────────────────────────────────────
if ($AwsPush) {
    Log "Pushing image to Amazon ECR"
    if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
        Die "aws CLI not found. Install: https://aws.amazon.com/cli/"
    }
    Import-CloudEnv -Required @('AWS_REGION', 'AWS_ACCOUNT_ID', 'AWS_ECR_REPOSITORY')

    $ecrUri = "$env:AWS_ACCOUNT_ID.dkr.ecr.$env:AWS_REGION.amazonaws.com/$env:AWS_ECR_REPOSITORY"
    $tag = if ($env:IMAGE_TAG) { $env:IMAGE_TAG } else {
        $sha = (git rev-parse --short HEAD) 2>$null
        if ($sha) { $sha } else { Get-Date -Format 'yyyyMMdd-HHmmss' }
    }

    Log "Logging Docker in to ECR ($env:AWS_REGION)"
    aws ecr get-login-password --region $env:AWS_REGION | docker login --username AWS --password-stdin $ecrUri.Split('/')[0]

    Log "Building image"
    docker build -t "$ecrUri`:$tag" -t "$ecrUri`:latest" .

    Log "Pushing $ecrUri`:$tag"
    docker push "$ecrUri`:$tag"
    docker push "$ecrUri`:latest"

    Ok "Pushed $ecrUri`:$tag"
    Ok "App Runner will auto-pull if AutoDeploymentsEnabled=true."
    return
}

# ── Azure push ──────────────────────────────────────────────────────────────
if ($AzurePush) {
    Log "Pushing image to Azure Container Registry"
    if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
        Die "az CLI not found. Install: https://learn.microsoft.com/cli/azure/install-azure-cli"
    }
    Import-CloudEnv -Required @('AZURE_ACR_NAME', 'AZURE_RESOURCE_GROUP')

    $loginServer = "$env:AZURE_ACR_NAME.azurecr.io"
    $tag = if ($env:IMAGE_TAG) { $env:IMAGE_TAG } else {
        $sha = (git rev-parse --short HEAD) 2>$null
        if ($sha) { $sha } else { Get-Date -Format 'yyyyMMdd-HHmmss' }
    }

    Log "Logging Docker in to ACR"
    az acr login --name $env:AZURE_ACR_NAME

    Log "Building image"
    docker build -t "$loginServer/securecapita`:$tag" -t "$loginServer/securecapita`:latest" .

    Log "Pushing $loginServer/securecapita`:$tag"
    docker push "$loginServer/securecapita`:$tag"
    docker push "$loginServer/securecapita`:latest"

    if ($env:AZURE_APP_SERVICE_NAME) {
        Log "Restarting App Service '$env:AZURE_APP_SERVICE_NAME' to pull the new image"
        az webapp restart --name $env:AZURE_APP_SERVICE_NAME --resource-group $env:AZURE_RESOURCE_GROUP
        Ok "App Service restart issued."
    } else {
        Ok "Image pushed. Set AZURE_APP_SERVICE_NAME in .env.cloud to auto-restart."
    }
    return
}

# ── .env handling ───────────────────────────────────────────────────────────
if (-not (Test-Path .env)) {
    if (Test-Path .env.example) {
        Warn ".env not found - copying from .env.example."
        Warn "EDIT .env and change MYSQL_ROOT_PASSWORD and JWT_SECRET before going anywhere near production."
        Copy-Item .env.example .env
    } else {
        Die "Neither .env nor .env.example present."
    }
}

$envText = Get-Content .env -Raw
if ($envText -match '(?m)^(MYSQL_ROOT_PASSWORD=change-me|JWT_SECRET=replace-with)') {
    Warn "Your .env still has placeholder values. Fine for local dev; not for anything exposed."
}
Ok ".env present"

# ── Optional clean ──────────────────────────────────────────────────────────
if ($Clean) {
    Log "Wiping previous stack + DB volume (-Clean)"
    docker compose down -v
    Ok "Volume wiped — fresh schema next start"
}

# ── Build ───────────────────────────────────────────────────────────────────
Log "Building images (Angular + Maven — this is the slow step)"
docker compose build
if ($LASTEXITCODE -ne 0) { Die "docker compose build failed." }
Ok "Images built"

# ── Start ───────────────────────────────────────────────────────────────────
Log "Starting stack in detached mode"
docker compose up -d
if ($LASTEXITCODE -ne 0) { Die "docker compose up failed." }
Ok "Containers running"

# ── Health wait ─────────────────────────────────────────────────────────────
Log "Waiting for app to report healthy (up to 3 minutes)..."
$appContainer = "securecapita-app"
$deadline = (Get-Date).AddMinutes(3)
$lastStatus = ""
while ((Get-Date) -lt $deadline) {
    $status = (docker inspect -f '{{.State.Health.Status}}' $appContainer 2>$null)
    if (-not $status) { $status = "unknown" }
    if ($status -ne $lastStatus) {
        Write-Host "   status: $status"
        $lastStatus = $status
    }
    if ($status -eq "healthy") { break }
    if ($status -eq "unhealthy") {
        Warn "Container reported unhealthy. Recent logs:"
        docker compose logs --tail=80 app
        Die "App failed to become healthy."
    }
    Start-Sleep -Seconds 3
}

if ($lastStatus -ne "healthy") {
    Warn "Timed out waiting for healthy. Recent logs:"
    docker compose logs --tail=80 app
    Die "Aborting — check logs above."
}
Ok "App is healthy"

# ── Done ────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "Deployment complete." -ForegroundColor Green
Write-Host ""
Write-Host "  App      http://localhost:8080"
Write-Host "  Health   http://localhost:8080/actuator/health"
Write-Host "  Adminer  http://localhost:8081   (server: mysql, user: root, db: db2)"
Write-Host ""
Write-Host "Useful commands:"
Write-Host "  docker compose logs -f app     # follow app logs"
Write-Host "  docker compose logs -f mysql   # follow db logs"
Write-Host "  docker compose ps              # see service status"
Write-Host "  .\deploy.ps1 -Down             # stop stack"
Write-Host "  .\deploy.ps1 -Clean            # wipe DB volume and rebuild"
Write-Host ""

if ($Logs) {
    Log "Tailing app logs (Ctrl-C to detach — containers keep running)"
    docker compose logs -f app
}
