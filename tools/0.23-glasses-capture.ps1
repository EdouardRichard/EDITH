#requires -Version 5.1
<#
.SYNOPSIS
    Capture EDITH glasses screenshots driven by the instrumentation marker file (0.23 P3/P4 evidence).

.DESCRIPTION
    GlassesVisualStateTest / GlassesInteractionTest write a state name to
    /sdcard/Android/data/com.sheen.rokid.glasses/files/<Marker>; this script polls it and takes one
    `adb exec-out screencap -p` per state change. Screenshots are the only evidence produced here - no
    device setting, no app data and no phone state is touched.

    `screencap` is used instead of `screenrecord`: screenrecord is all-black on this host (official UI
    archive, 2026-10-17). The binary redirect goes through cmd.exe because PowerShell would corrupt the PNG.

.EXAMPLE
    pwsh tools/0.23-glasses-capture.ps1 -States empty,listening,thinking,multiturn -OutDir docs/project/evidence/assets/2026-10-18-0.23-p3 -StartTest GlassesVisualStateTest
#>
param(
    [string[]] $States = @(),
    [Parameter(Mandatory = $true)] [string] $OutDir,
    [string] $Serial = '1901092544027662',
    [string] $Marker = '/sdcard/Android/data/com.sheen.rokid.glasses/files/visual-state',
    [string] $StartTest = '',
    [int] $TimeoutSeconds = 90,
    [int] $SettleMilliseconds = 900
)

$ErrorActionPreference = 'Stop'
# `powershell -File script.ps1 -States a,b,c` (Windows PowerShell 5.1) delivers one string, not an array.
if ($States.Count -eq 1 -and $States[0].Contains(',')) { $States = $States[0].Split(',') }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
# A stale `done` from a previous run would make the poll loop exit before the test writes its first state.
adb -s $Serial shell "rm -f $Marker" 2>$null | Out-Null

if ($StartTest) {
    $null = Start-Job -ScriptBlock {
        param($serial, $test)
        adb -s $serial shell am instrument -w -e class "com.sheen.rokid.glasses.$test" `
            com.sheen.rokid.glasses.test/androidx.test.runner.AndroidJUnitRunner
    } -ArgumentList $Serial, $StartTest
}

$last = ''
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $state = (adb -s $Serial shell "cat $Marker 2>/dev/null" | Out-String).Trim()
    if ($state -and $state -ne $last) {
        if ($state -eq 'done') { $last = $state; break }
        if ($States.Count -eq 0 -or $States -contains $state) {
            Start-Sleep -Milliseconds $SettleMilliseconds
            $path = Join-Path $OutDir "$state.png"
            cmd /c "adb -s $Serial exec-out screencap -p > `"$path`""
            $size = if (Test-Path $path) { (Get-Item $path).Length } else { 0 }
            Write-Output "$state -> $size bytes -> $path"
        }
        $last = $state
    }
    Start-Sleep -Milliseconds 250
}
Write-Output "final=$last"

if ($StartTest) {
    Get-Job | Wait-Job | Receive-Job
    Get-Job | Remove-Job
}
