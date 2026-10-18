# Build the static web bundle into dist/ without relying on npm/pnpm scripts.
#
# Requiring: Node.js 22.12+ and installed dependencies (`npm ci`).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $Node) {
    throw 'Node.js was not found in PATH. Install Node.js 22.12 or newer first.'
}
if (-not (Test-Path (Join-Path $Root 'node_modules'))) {
    throw 'node_modules is missing. Run `npm ci` in the project root first.'
}
Set-Location $Root

Write-Host '[1/4] export records'
& $Node scripts\export-records.mjs
if ($LASTEXITCODE -ne 0) { throw 'export-records failed' }

Write-Host '[2/4] tsc'
& $Node node_modules\typescript\bin\tsc
if ($LASTEXITCODE -ne 0) { throw 'tsc failed' }

Write-Host '[3/4] vite build'
& $Node node_modules\vite\bin\vite.js build
if ($LASTEXITCODE -ne 0) { throw 'vite build failed' }

Write-Host '[4/4] build-pwa'
& $Node scripts\build-pwa.mjs
if ($LASTEXITCODE -ne 0) { throw 'build-pwa failed' }

Write-Host 'Web build done: dist/'
