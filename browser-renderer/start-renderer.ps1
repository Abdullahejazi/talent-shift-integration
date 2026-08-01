$ErrorActionPreference = 'Stop'
$rendererDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $rendererDirectory
if (-not (Test-Path 'node_modules')) { npm.cmd install }
if (-not (Test-Path "$env:LOCALAPPDATA\ms-playwright")) { npx.cmd playwright install chromium }
npm.cmd start
