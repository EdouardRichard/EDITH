param(
    [hashtable]$Devices = @{ K40 = '2696813b' },
    [string]$LogDir = "$PSScriptRoot/../../../.local/p3-t3",
    [string]$Adb = 'D:/Tools/Android/Sdk/platform-tools/adb.exe',
    [switch]$AllowDisruptive,
    [switch]$Run
)

$ErrorActionPreference = 'Stop'

# P3.0 T3a: what can the shell-UID execution backend actually do on this hardware?
#
# `adb shell` runs as the **shell UID (2000)** and Shizuku grants the same UID, so this host-side matrix measures the
# Shizuku backend's real ceiling without writing an app first.
#
# ROOT IS DELIBERATELY NOT PROBED. The user's decision (2026-10-08): Root is a case that exists in the design as a
# possibility, and it does not need verifying. The matrix therefore measures one backend only, and the P3 risk table
# keeps L4 as a described capability rather than a measured one.
#
# Two traps this script is built to avoid:
#
#   1. **Exit code is not success.** `input tap` can exit 0 while printing `Error: Injecting event failed`; a matrix
#      built on exit codes would report OK for actions that never happened, and the P3 risk table would then rest on
#      fiction.
#   2. **A partial matrix is not a matrix.** An absent device is UNTESTED, never a missing row.
#
# The device loop runs only under `-Run`; dot-sourcing this file is inert so the self-test can exercise the verdicts.
#
# NOTE: this file is deliberately **pure ASCII**. PowerShell 5.1 decodes a BOM-less `.ps1` as ANSI/CP936, and every edit
# tool that rewrites this file strips the BOM; non-ASCII literals here failed to parse twice because of exactly that.
# The Chinese narrative belongs in the evidence files, which are never executed.

$script:FailureMarkers = @(
    'Error:', 'Exception', 'Permission denial', 'SecurityException',
    'Unknown command', 'Unsupported', 'not allowed', 'denied', 'Operation not permitted'
)

# Actions the P3 design has to reason about. `disruptive` actions change connectivity or display and are therefore
# SKIPPED (recorded as UNTESTED) unless -AllowDisruptive is passed: disabling Wi-Fi mid-probe would cut the glasses link
# the rest of the session depends on.
$script:ActionMatrix = @(
    @{ name = 'id';                             cmd = 'id';                                                             disruptive = $false }
    @{ name = 'pm list packages';               cmd = 'pm list packages';                                               disruptive = $false }
    @{ name = 'input tap';                      cmd = 'input tap 500 500';                                              disruptive = $false; weak = $true; restores = $true }
    @{ name = 'input swipe';                    cmd = 'input swipe 500 1000 500 500 200';                               disruptive = $false; weak = $true; restores = $true }
    @{ name = 'input text';                     cmd = 'input text edith';                                               disruptive = $false; weak = $true; restores = $true }
    @{ name = 'am start -n';                    cmd = 'am start -n com.android.settings/.Settings';                     disruptive = $false; restores = $true }
    @{ name = 'am force-stop';                  cmd = 'am force-stop com.rokid.renewcxrlsample';                        disruptive = $false }
    @{ name = 'cmd notification list';          cmd = 'cmd notification list';                                          disruptive = $false }
    @{ name = 'cmd statusbar expand+collapse';  cmd = 'cmd statusbar expand-notifications; cmd statusbar collapse';  disruptive = $false }
    @{ name = 'settings get';                   cmd = 'settings get global edith_p3_probe';                             disruptive = $false }
    @{ name = 'settings put';                   cmd = 'settings put global edith_p3_probe 1';                           disruptive = $false }
    @{ name = 'dumpsys window';                 cmd = 'dumpsys window';                                                 disruptive = $false }
    @{ name = 'wm size (query only)';           cmd = 'wm size';                                                        disruptive = $false }
    @{ name = 'cat /data/data (expect denied)'; cmd = 'cat /data/data/com.android.settings/shared_prefs';               disruptive = $false }
    # `weak`: the exit code cannot answer the question. `pm grant` probed against POST_NOTIFICATIONS exited 0 while
    # `dumpsys package` showed granted=true, flags=[SYSTEM_FIXED|GRANTED_BY_DEFAULT] - a no-op on an already-granted,
    # system-fixed permission, and the command exits 0 for that too. Reporting OK would be a false capability claim.
    @{ name = 'pm grant (exit code not meaningful)'; cmd = 'pm grant com.android.settings android.permission.CAMERA'; disruptive = $false; weak = $true }
    @{ name = 'svc wifi disable';               cmd = 'svc wifi disable';                                               disruptive = $true }
)

# --------------------------------------------------------------------------- pure: verdicts

<#
    One action's verdict. A clean exit is not enough: several shell commands report failure on stdout while exiting 0,
    and treating those as OK is how a capability matrix becomes fiction.
#>
function Get-ActionVerdict {
    param([int]$ExitCode, [string]$Stdout)
    if ($ExitCode -ne 0) { return 'BLOCKED' }
    $text = if ($null -eq $Stdout) { '' } else { $Stdout }
    foreach ($marker in $script:FailureMarkers) {
        if ($text -match [regex]::Escape($marker)) { return 'BLOCKED' }
    }
    return 'OK'
}

<#
    The row's verdict, including the third failure mode the device run exposed: an action whose **exit code cannot
    answer the question at all**. `pm grant` is the live example — it exits 0 whether or not it changed anything. Such
    an action is recorded as INCONCLUSIVE so the evidence file scores it against a real state check rather than
    manufacturing a capability claim from a meaningless zero.
#>
function Get-RowVerdict {
    param([bool]$Weak, [int]$ExitCode, [string]$Stdout)
    if ($Weak) { return 'INCONCLUSIVE' }
    return Get-ActionVerdict -ExitCode $ExitCode -Stdout $Stdout
}

<#
    Whether the shell backend is really the shell backend. It must answer with the shell uid; a privileged shell
    answering on its behalf means the two have been blurred together and the matrix would overstate what the path can do.
#>
function Get-ShellBackendVerdict {
    param([int]$ExitCode, [string]$Stdout)
    if ($ExitCode -ne 0) { return 'BLOCKED' }
    $text = if ($null -eq $Stdout) { '' } else { $Stdout }
    if ($text -match 'uid=2000\(') { return 'OK' }
    return 'BLOCKED'
}

<#
    Coverage: every expected action must appear. Absent devices must be visible as UNTESTED rows, so a K70 that was not
    on the desk cannot quietly become "K40's results".
#>
function Assert-EveryActionRecorded {
    param(
        [object[]]$Rows,
        [string[]]$ExpectedActions,
        [string[]]$ExpectedBackends,
        [string[]]$ExpectedDevices = @()
    )
    if ($null -eq $Rows -or $Rows.Count -eq 0) { return $false }
    foreach ($action in $ExpectedActions) {
        foreach ($backend in $ExpectedBackends) {
            $match = $Rows | Where-Object { $_.action -eq $action -and $_.backend -eq $backend }
            if (-not $match) { return $false }
        }
    }
    foreach ($device in $ExpectedDevices) {
        $match = $Rows | Where-Object { $_.device -eq $device }
        if (-not $match) { return $false }
    }
    return $true
}

# --------------------------------------------------------------------------- device

function Invoke-AdbRaw {
    param([string]$AdbPath, [string[]]$Arguments)
    # The project's recurring PowerShell 5.1 trap: with `$ErrorActionPreference = 'Stop'`, a native command's **stderr**
    # becomes a terminating error, and `adb` uses stderr for ordinary notices. An expected, informative line must not
    # abort the whole matrix. The preference is relaxed only around the call, so the pure logic keeps failing fast.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $AdbPath @Arguments 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return @{ exitCode = $code; stdout = $text }
}

function Get-ActionMatrix {
    param(
        [hashtable]$DeviceMap,
        [string]$AdbPath,
        [string]$OutputDirectory,
        [switch]$IncludeDisruptive
    )
    [IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
    $rows = @()

    foreach ($label in $DeviceMap.Keys) {
        $serial = $DeviceMap[$label]
        $probe = Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('devices')
        $present = $probe.stdout -match [regex]::Escape($serial)
        if (-not $present) {
            # Absence is a recorded result, not an omission.
            foreach ($action in $script:ActionMatrix) {
                $rows += @{ device = $label; action = $action.name; backend = 'shell'
                            exitCode = $null; stdout = ''; verdict = 'UNTESTED'
                            note = 'device not attached during this run' }
            }
            # Write-Host, not Write-Output: this function's success stream is its return value, and `$rows = Get-ActionMatrix`
            # would swallow every diagnostic line into the row list (observed: 18 "rows" for 16 actions, no lines printed).
            Write-Host "P3_T3A_DEVICE_ABSENT $label ($serial) -> all rows UNTESTED"
            continue
        }

        # Re-confirm the backend now; a previous run's authorisation proves nothing about this one.
        $shellProbe = Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $serial, 'shell', 'id')
        $shellOk = (Get-ShellBackendVerdict -ExitCode $shellProbe.exitCode -Stdout $shellProbe.stdout) -eq 'OK'
        Write-Host "P3_T3A_BACKEND device=$label shell=$shellOk"

        foreach ($action in $script:ActionMatrix) {
            if (-not $shellOk) {
                $rows += @{ device = $label; action = $action.name; backend = 'shell'
                            exitCode = $null; stdout = ''; verdict = 'UNTESTED'
                            note = 'shell backend unavailable at probe time' }
                continue
            }
            if ($action.disruptive -and -not $IncludeDisruptive) {
                $rows += @{ device = $label; action = $action.name; backend = 'shell'
                            exitCode = $null; stdout = ''; verdict = 'UNTESTED'
                            note = 'disruptive action skipped by design (would cut connectivity); pass -AllowDisruptive to run' }
                continue
            }
            $result = Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $serial, 'shell', $action.cmd)
            $verdict = Get-RowVerdict -Weak ([bool]$action.weak) -ExitCode $result.exitCode -Stdout $result.stdout
            $note = if ($action.weak) {
                'INCONCLUSIVE by design: this command exit-codes 0 whether or not it changed anything; see the evidence file'
            } else { '' }
            # stdout is truncated: dumpsys and cmd output can carry account strings and device identifiers that have no
            # business in an evidence file.
            $stdout = if ($result.stdout.Length -gt 400) { $result.stdout.Substring(0, 400) } else { $result.stdout }
            $rows += @{ device = $label; action = $action.name; backend = 'shell'
                        exitCode = $result.exitCode; stdout = $stdout; verdict = $verdict; note = $note }
            if ($action.restores) {
                # Learned the hard way: `am start` + `input tap` + `input text edith` left the operator's phone sitting
                # in the Settings search screen with "edith" typed into it, and nothing put it back. An action that can
                # visibly change the device must leave it as it found it, and say that it did.
                Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $serial, 'shell', 'am', 'force-stop', 'com.android.settings') | Out-Null
                Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
                Write-Host "P3_T3A_RESTORED action='$($action.name)' -> settings force-stopped, HOME"
            }
            Write-Host ("P3_T3A_ROW device={0} backend=shell action='{1}' verdict={2} exit={3}" -f `
                $label, $action.name, $verdict, $result.exitCode)
        }
    }

    $reportPath = Join-Path $OutputDirectory "shell-uid-matrix-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')).json"
    @{ schemaVersion = 1; measuredBackend = 'shell'; rootProbed = $false; devices = $DeviceMap; rows = $rows } |
        ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
    Write-Host "P3_T3A_REPORT $reportPath"
    return , $rows
}

if ($Run) {
    $rows = Get-ActionMatrix -DeviceMap $Devices -AdbPath $Adb -OutputDirectory $LogDir -IncludeDisruptive:$AllowDisruptive
    $actions = $script:ActionMatrix | ForEach-Object { $_.name }
    $coverage = Assert-EveryActionRecorded -Rows $rows -ExpectedActions $actions -ExpectedBackends @('shell') `
        -ExpectedDevices @($Devices.Keys)
    Write-Host "P3_T3A_COVERAGE_AND_VERDICT rows=$($rows.Count) complete=$coverage"
}
