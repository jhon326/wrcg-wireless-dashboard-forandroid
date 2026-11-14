$ErrorActionPreference = "Stop"

Set-Location -LiteralPath $PSScriptRoot
Write-Host "正在启动 WRCG 无线仪表盘..."
node src/desktop-entry.js
