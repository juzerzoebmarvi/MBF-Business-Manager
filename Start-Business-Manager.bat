@echo off
title Business Manager - Marvi's Bohra Food
echo ================================================================
echo    Business Manager - Desktop & Laptop Web App
echo    Starting local server on localhost (No internet server errors)...
echo ================================================================
echo.

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop';" ^
  "function Open-AppWindow([string]$targetUrl) {" ^
  "  $edge = @(" ^
  "    \"$env:ProgramFiles(x86)\Microsoft\Edge\Application\msedge.exe\"," ^
  "    \"$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe\"" ^
  "  ) | Where-Object { Test-Path $_ } | Select-Object -First 1;" ^
  "  $chrome = @(" ^
  "    \"$env:ProgramFiles\Google\Chrome\Application\chrome.exe\"," ^
  "    \"$env:ProgramFiles(x86)\Google\Chrome\Application\chrome.exe\"," ^
  "    \"$env:LocalAppData\Google\Chrome\Application\chrome.exe\"" ^
  "  ) | Where-Object { Test-Path $_ } | Select-Object -First 1;" ^
  "  if ($chrome) { Start-Process $chrome \"--app=$targetUrl\" }" ^
  "  elseif ($edge) { Start-Process $edge \"--app=$targetUrl\" }" ^
  "  else { Start-Process $targetUrl }" ^
  "}" ^
  "try {" ^
  "  $probe = Invoke-WebRequest -Uri 'http://localhost:3000/health' -UseBasicParsing -TimeoutSec 1;" ^
  "  if ($probe.StatusCode -eq 200) {" ^
  "    Write-Host 'Business Manager server is already running at http://localhost:3000/' -ForegroundColor Green;" ^
  "    Open-AppWindow 'http://localhost:3000/';" ^
  "    exit 0;" ^
  "  }" ^
  "} catch {}" ^
  "$listener = $null;" ^
  "$port = 3000;" ^
  "foreach ($candidate in @(3000, 3001, 3005, 3010, 8090)) {" ^
  "  try {" ^
  "    $tryListener = New-Object System.Net.HttpListener;" ^
  "    $tryListener.Prefixes.Add(\"http://localhost:$candidate/\");" ^
  "    $tryListener.Start();" ^
  "    $listener = $tryListener;" ^
  "    $port = $candidate;" ^
  "    break;" ^
  "  } catch {" ^
  "    if ($tryListener) { try { $tryListener.Close() } catch {} }" ^
  "  }" ^
  "}" ^
  "if (-not $listener) {" ^
  "  Write-Host 'ERROR: Could not bind any localhost port (3000, 3001, 3005, 3010, 8090).' -ForegroundColor Red;" ^
  "  pause; exit 1;" ^
  "}" ^
  "$url = \"http://localhost:$port/\";" ^
  "Write-Host \"Server running cleanly at $url\" -ForegroundColor Green;" ^
  "Write-Host 'Opening Business Manager app window...' -ForegroundColor Yellow;" ^
  "Open-AppWindow $url;" ^
  "Write-Host '';" ^
  "Write-Host '----------------------------------------------------------------' -ForegroundColor Cyan;" ^
  "Write-Host '   KEEP THIS WINDOW OPEN WHILE USING BUSINESS MANAGER          ' -ForegroundColor Cyan;" ^
  "Write-Host '   Offline + Real-Time Cloud Sync enabled on this laptop        ' -ForegroundColor Cyan;" ^
  "Write-Host '   When finished, simply close this window.                     ' -ForegroundColor Cyan;" ^
  "Write-Host '----------------------------------------------------------------' -ForegroundColor Cyan;" ^
  "$rootDir = (Get-Location).Path;" ^
  "while ($listener.IsListening) {" ^
  "  $context = $null;" ^
  "  try {" ^
  "    $context = $listener.GetContext();" ^
  "    $req = $context.Request;" ^
  "    $resp = $context.Response;" ^
  "    $resp.Headers.Add('Access-Control-Allow-Origin', '*');" ^
  "    $localPath = [System.Uri]::UnescapeDataString($req.Url.AbsolutePath).TrimStart('/');" ^
  "    if ($localPath -eq 'health') {" ^
  "      $okBytes = [System.Text.Encoding]::UTF8.GetBytes('{\"status\":\"ok\",\"app\":\"Business Manager\"}');" ^
  "      $resp.ContentType = 'application/json; charset=utf-8';" ^
  "      $resp.ContentLength64 = $okBytes.Length;" ^
  "      if ($req.HttpMethod -ne 'HEAD') { $resp.OutputStream.Write($okBytes, 0, $okBytes.Length); }" ^
  "      $resp.Close();" ^
  "      continue;" ^
  "    }" ^
  "    if ([string]::IsNullOrEmpty($localPath) -or $localPath -eq '/') { $localPath = 'index.html'; }" ^
  "    $safeRel = $localPath.Replace('/', [System.IO.Path]::DirectorySeparatorChar);" ^
  "    $filePath = [System.IO.Path]::GetFullPath((Join-Path $rootDir $safeRel));" ^
  "    if (-not $filePath.StartsWith($rootDir) -or -not (Test-Path $filePath -PathType Leaf)) {" ^
  "      $filePath = Join-Path $rootDir 'index.html';" ^
  "    }" ^
  "    $bytes = [System.IO.File]::ReadAllBytes($filePath);" ^
  "    $ext = [System.IO.Path]::GetExtension($filePath).ToLower();" ^
  "    switch ($ext) {" ^
  "      '.html' { $resp.ContentType = 'text/html; charset=utf-8'; $resp.Headers.Add('Cache-Control', 'no-cache'); }" ^
  "      '.js'   { $resp.ContentType = 'application/javascript; charset=utf-8'; $resp.Headers.Add('Cache-Control', 'no-cache'); }" ^
  "      '.css'  { $resp.ContentType = 'text/css; charset=utf-8'; }" ^
  "      '.png'  { $resp.ContentType = 'image/png'; }" ^
  "      '.ico'  { $resp.ContentType = 'image/x-icon'; }" ^
  "      '.svg'  { $resp.ContentType = 'image/svg+xml'; }" ^
  "      '.webp' { $resp.ContentType = 'image/webp'; }" ^
  "      '.json' { $resp.ContentType = 'application/json; charset=utf-8'; }" ^
  "      '.webmanifest' { $resp.ContentType = 'application/manifest+json; charset=utf-8'; }" ^
  "      default { $resp.ContentType = 'application/octet-stream'; }" ^
  "    }" ^
  "    $resp.ContentLength64 = $bytes.Length;" ^
  "    if ($req.HttpMethod -ne 'HEAD') { $resp.OutputStream.Write($bytes, 0, $bytes.Length); }" ^
  "    $resp.Close();" ^
  "  } catch {" ^
  "    if ($context -and $context.Response) { try { $context.Response.Abort() } catch {} }" ^
  "  }" ^
  "}"
pause
