<#
  Build the Android APK on jni-server inside a Docker container, then fetch it.

  Usage:
    powershell -File scripts/build-on-vps.ps1 -ApiKey "<key>" -BaseUrl "https://dl.jni.my.id/"
    powershell -File scripts/build-on-vps.ps1 -Mode test          # run unit tests on the VPS

  Requires: a committed git HEAD (uses `git archive`), ssh access to the server.

  NOTE: We do NOT set ErrorActionPreference=Stop, because Windows PowerShell 5.1
  treats a native command's stderr output (e.g. docker warnings) as an error record.
  Instead we check $LASTEXITCODE after each native call.
#>
param(
  [string]$Server  = "jni-server",
  [string]$Remote  = "/data/build/video-downloader",
  [string]$ApiKey  = $(if ($env:BACKEND_API_KEY) { $env:BACKEND_API_KEY } else { "dev-key" }),
  [string]$BaseUrl = "https://dl.jni.my.id/",
  [ValidateSet("assemble","test")] [string]$Mode = "assemble"
)
Set-Location (Split-Path $PSScriptRoot -Parent)

function Assert-Ok($what) {
  if ($LASTEXITCODE -ne 0) { Write-Error "$what failed (exit $LASTEXITCODE)"; exit 1 }
}

Write-Host "==> Packaging source (git archive HEAD)..."
git archive --format=tar.gz -o src.tgz HEAD
Assert-Ok "git archive"

Write-Host "==> Sending source to $Server`:$Remote ..."
ssh $Server "mkdir -p $Remote"
Assert-Ok "ssh mkdir"
scp src.tgz "${Server}:${Remote}/src.tgz"
Assert-Ok "scp src"
Remove-Item src.tgz -ErrorAction SilentlyContinue
ssh $Server "cd $Remote && tar xzf src.tgz && rm -f src.tgz"
Assert-Ok "remote extract"

Write-Host "==> Building Docker build image (cached after first run)..."
ssh $Server "cd $Remote && DOCKER_BUILDKIT=1 docker build -f Dockerfile.build -t android-build:34 ."
Assert-Ok "docker build image"

Write-Host "==> Running build ($Mode) in container..."
$run = "cd $Remote && docker run --rm " +
       "-v ${Remote}:/workspace " +
       "-v android-gradle-cache:/root/.gradle " +
       "-e BACKEND_API_KEY='$ApiKey' -e BACKEND_BASE_URL='$BaseUrl' " +
       "android-build:34 bash scripts/build-inside-container.sh $Mode"
ssh $Server $run
Assert-Ok "container build"

if ($Mode -eq "assemble") {
  Write-Host "==> Fetching APK..."
  New-Item -ItemType Directory -Force dist | Out-Null
  scp "${Server}:${Remote}/app/build/outputs/apk/debug/app-debug.apk" dist/app-debug.apk
  Assert-Ok "scp apk"
  Write-Host "APK -> dist/app-debug.apk"
}
