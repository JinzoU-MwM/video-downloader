<#
  Build the Android APK on jni-server inside a Docker container, then fetch it.

  Usage:
    pwsh scripts/build-on-vps.ps1 -ApiKey "<key>" -BaseUrl "https://dl.jni.my.id/"
    pwsh scripts/build-on-vps.ps1 -Mode test          # run unit tests on the VPS

  Requires: a committed git HEAD (uses `git archive`), ssh access to the server.
#>
param(
  [string]$Server  = "jni-server",
  [string]$Remote  = "/data/build/video-downloader",
  [string]$ApiKey  = $(if ($env:BACKEND_API_KEY) { $env:BACKEND_API_KEY } else { "dev-key" }),
  [string]$BaseUrl = "https://dl.jni.my.id/",
  [ValidateSet("assemble","test")] [string]$Mode = "assemble"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "==> Packaging source (git archive HEAD)..."
git archive --format=tar.gz -o src.tgz HEAD

Write-Host "==> Sending source to $Server`:$Remote ..."
ssh $Server "mkdir -p $Remote"
scp src.tgz "${Server}:${Remote}/src.tgz"
Remove-Item src.tgz -ErrorAction SilentlyContinue
ssh $Server "cd $Remote && tar xzf src.tgz && rm src.tgz"

Write-Host "==> Building Docker build image (cached after first run)..."
ssh $Server "cd $Remote && docker build -f Dockerfile.build -t android-build:34 ."

Write-Host "==> Running build ($Mode) in container..."
$run = "cd $Remote && docker run --rm " +
       "-v ${Remote}:/workspace " +
       "-v android-gradle-cache:/root/.gradle " +
       "-e BACKEND_API_KEY='$ApiKey' -e BACKEND_BASE_URL='$BaseUrl' " +
       "android-build:34 bash scripts/build-inside-container.sh $Mode"
ssh $Server $run

if ($Mode -eq "assemble") {
  Write-Host "==> Fetching APK..."
  New-Item -ItemType Directory -Force dist | Out-Null
  scp "${Server}:${Remote}/app/build/outputs/apk/debug/app-debug.apk" dist/app-debug.apk
  Write-Host "APK -> dist/app-debug.apk"
}
