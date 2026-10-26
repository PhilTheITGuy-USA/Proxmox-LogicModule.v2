<#
.SYNOPSIS
    Imports the built suite (dist\*.json) into a LogicMonitor portal, overwriting modules of the
    same name.

.DESCRIPTION
    Uses the community Logic.Monitor PowerShell module (PowerShell 7+, from the PowerShell
    Gallery): run Connect-LMAccount first, with a token whose role can Manage modules.

    Import-LMLogicModuleFromFile takes DataSources and TopologySources. It has no PropertySource
    type, so the suite's PropertySources (Proxmox_VE_Info, addERI_*) are listed at the end for
    the portal's own import (Modules > My Module Toolbox > Add > Import from file).

    Nothing is preserved: every field comes from the build, thresholds and AppliesTo included.
    For a portal where users tune modules, pass -Preserve "DATAPOINT_ALERT_THRESHOLDS,APPLIES_TO_SCRIPT".

.EXAMPLE
    Connect-LMAccount -AccessId '<id>' -AccessKey '<key>' -AccountName '<portal>'
    .\tools\Import-Modules.ps1
#>
param(
    [string]$Dist = (Join-Path $PSScriptRoot '..\dist'),
    [string]$Preserve = ''
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Logic.Monitor needs PowerShell 7 or later (pwsh).' }
Import-Module Logic.Monitor

# LogicMonitor's module type ids, as the build writes them: 0 DataSource, 5 PropertySource,
# 9 TopologySource.
$types = @{ 0 = 'datasources'; 9 = 'topologysources' }
$byHand = @()
$failed = @()

Get-ChildItem -Path $Dist -Filter '*.json' | Sort-Object Name | ForEach-Object {
    $module = Get-Content -Path $_.FullName -Raw | ConvertFrom-Json
    $type = $types[[int]$module.type]
    if (-not $type) {
        $byHand += $_.Name
        return
    }
    try {
        $arguments = @{ FilePath = $_.FullName; Type = $type; Format = 'json'; HandleConflict = 'FORCE_OVERWRITE' }
        if ($Preserve) { $arguments.FieldsToPreserve = $Preserve }
        Import-LMLogicModuleFromFile @arguments | Out-Null
        Write-Host ('imported  {0,-16} {1}' -f $type, $module.name)
    } catch {
        $failed += $_.Name
        Write-Warning ('FAILED    {0}: {1}' -f $module.name, $_.Exception.Message)
    }
}

if ($byHand) {
    Write-Host ''
    Write-Host 'Import these PropertySources in the portal (Modules > My Module Toolbox > Add > Import from file):'
    $byHand | ForEach-Object { Write-Host ('  ' + (Join-Path (Resolve-Path $Dist) $_)) }
}
if ($failed) { exit 1 }
