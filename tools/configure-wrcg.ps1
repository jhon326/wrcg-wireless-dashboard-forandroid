[CmdletBinding()]
param(
    [switch]$Enable,
    [switch]$Disable,
    [switch]$Status
)

$ErrorActionPreference = "Stop"
$ConfigPath = Join-Path $env:USERPROFILE "Documents\My Games\WRCG\UserSettings.cfg"

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw "WRCG configuration was not found: $ConfigPath"
}

function Get-SettingValue {
    param(
        [string]$Content,
        [string]$Name
    )

    $Pattern = "(?m)^\s*" + [regex]::Escape($Name) + "\s*=\s*(.*?);\s*$"
    $Match = [regex]::Match($Content, $Pattern)
    if ($Match.Success) {
        return $Match.Groups[1].Value.Trim()
    }
    return "<not set>"
}

function Set-SettingValue {
    param(
        [string]$Content,
        [string]$Name,
        [string]$Value
    )

    $Pattern = "(?m)^\s*" + [regex]::Escape($Name) + "\s*=\s*.*?;\s*$"
    $Replacement = "$Name = $Value;"
    if ([regex]::IsMatch($Content, $Pattern)) {
        return [regex]::Replace($Content, $Pattern, $Replacement, 1)
    }
    return $Content.TrimEnd() + [Environment]::NewLine + $Replacement + [Environment]::NewLine
}

$Current = [System.IO.File]::ReadAllText($ConfigPath)

if ($Status -or (-not $Enable -and -not $Disable)) {
    Write-Host "WRCG telemetry settings"
    Write-Host "  Config file: $ConfigPath"
    Write-Host "  Enabled:     $(Get-SettingValue $Current 'WRC.Telemetry.EnableTelemetry')"
    Write-Host "  Address:     $(Get-SettingValue $Current 'WRC.Telemetry.TelemetryAdress')"
    Write-Host "  Port:        $(Get-SettingValue $Current 'WRC.Telemetry.TelemetryPort')"
    Write-Host "  Rate:        $(Get-SettingValue $Current 'WRC.Telemetry.TelemetryRate') Hz"
    exit 0
}

$Running = Get-Process -Name "WRCG" -ErrorAction SilentlyContinue
if ($Running) {
    throw "Close WRCG before changing its telemetry settings."
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupPath = "$ConfigPath.wrcg-dashboard-$Stamp.bak"
Copy-Item -LiteralPath $ConfigPath -Destination $BackupPath

$Updated = $Current
if ($Enable) {
    $Updated = Set-SettingValue $Updated "WRC.Telemetry.EnableTelemetry" "true"
    $Updated = Set-SettingValue $Updated "WRC.Telemetry.TelemetryAdress" '"127.0.0.1"'
    $Updated = Set-SettingValue $Updated "WRC.Telemetry.TelemetryPort" "20777"
    $Updated = Set-SettingValue $Updated "WRC.Telemetry.TelemetryRate" "30"
}

if ($Disable) {
    $Updated = Set-SettingValue $Updated "WRC.Telemetry.EnableTelemetry" "false"
}

$Utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($ConfigPath, $Updated, $Utf8WithoutBom)

Write-Host "WRCG telemetry settings updated."
Write-Host "  Backup file: $BackupPath"
Write-Host "  Enabled:     $(Get-SettingValue $Updated 'WRC.Telemetry.EnableTelemetry')"
Write-Host "  Target:      $(Get-SettingValue $Updated 'WRC.Telemetry.TelemetryAdress'):$(Get-SettingValue $Updated 'WRC.Telemetry.TelemetryPort')"
Write-Host "  Rate:        $(Get-SettingValue $Updated 'WRC.Telemetry.TelemetryRate') Hz"
