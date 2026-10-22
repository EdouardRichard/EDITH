param(
    [int]$Minutes = 30,
    [int]$SampleSeconds = 60,
    [string]$LogDir = "$PSScriptRoot/../../../.local/p3-t2",
    [string]$Adb = 'D:/Tools/Android/Sdk/platform-tools/adb.exe',
    [string]$GlassesSerial = '1901092544027662',
    [string]$PhoneSerial = '2696813b',
    [switch]$Run
)

$ErrorActionPreference = 'Stop'

# P3.0 T2: does the glasses -> phone PCM uplink survive a real 30-minute session, including a screen lock?
#
# The device loop runs only under `-Run`; dot-sourcing this file is inert so `soak-uplink.tests.ps1` can exercise the
# parsing and verdict functions without hardware. Everything that decides pass/fail is a pure function for that reason:
# a soak whose numbers were mis-parsed, or whose zero-byte window was averaged away, produces a confident wrong answer.
#
# D33 boundary: this script never reads or writes audio. It counts the probe's own reported bytes and timestamps.

# --------------------------------------------------------------------------- pure: parsing

<#
    Turns raw `EdithAudioProbe` logcat lines into one sample per measurement window.

    A window is only trustworthy if its `PROBE_UPLINK_START` line was seen: the probe Activity is usually already the
    top-most instance, so `am start` only reaches `onNewIntent`, and a mode wired into one branch but not the other
    produces a *silent no-op* that looks exactly like a broken uplink. `started` carries that fact into the verdict.

    `deltaBytes` equals the window's own `payloadBytes` because the probe reports **per run**, not cumulatively (one
    bounded run per window). The name is kept from the plan so the artifacts match its Interfaces block.
#>
function Convert-ProbeLinesToSamples {
    param([string[]]$Lines, [int]$SampleSeconds = 60)
    $samples = @()
    $started = $false
    $minute = 0
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -match 'PROBE_UPLINK_START') { $started = $true; continue }
        # Two probe modes report different fields and the first version of this parser read only one of them:
        #   uplink mode      -> `PROBE_UPLINK ... bytes=384000 ... refusal=none ...`
        #   throughput mode  -> `PROBE_THROUGHPUT accepted=... payloadBytes=384000 ... verdict=SUSTAINS_REAL_TIME`
        # The soak drives uplink mode, so looking only for `payloadBytes=` matched nothing and every window would have
        # been recorded NO_REPORT while the device was working. Both shapes are accepted; the byte field is whichever
        # is present.
        $bytes = $null
        if ($line -match 'bytes=(\d+)') { $bytes = [int64]$Matches[1] }
        elseif ($line -match 'payloadBytes=(\d+)') { $bytes = [int64]$Matches[1] }
        if ($null -eq $bytes) { continue }

        $refusal = if ($line -match 'refusal=(\w+)') { $Matches[1] } else { 'none' }
        $reported = if ($line -match 'verdict=([A-Z_]+)') { $Matches[1] } else { $null }
        # Healthy means "bytes actually crossed AND the transport refused nothing". An average rate that meets the
        # target while refusing frames is still a failure, which is why refusal is part of the verdict.
        $verdict = if ($bytes -gt 0 -and $refusal -eq 'none') {
            if ($reported) { $reported } else { 'SUSTAINS_REAL_TIME' }
        } else {
            if ($reported) { $reported } else { 'REFUSED' }
        }

        $minute++
        $samples += @{
            minute       = $minute
            payloadBytes = $bytes
            deltaBytes   = $bytes
            verdict      = $verdict
            started      = $started
        }
        $started = $false
    }
    return , $samples
}

<#
    The soak's single judgement. Every window must have been started, must have carried bytes, and must not have been
    refused by the transport. An empty run is not a pass: "we measured nothing" has never been evidence of stability.
#>
function Test-MonotonicGrowth {
    param([object[]]$PerMinute)
    if ($null -eq $PerMinute -or $PerMinute.Count -eq 0) { return $false }
    foreach ($sample in $PerMinute) {
        if ($sample.started -ne $true) { return $false }
        if ([int64]$sample.deltaBytes -le 0) { return $false }
        if ($sample.verdict -ne 'SUSTAINS_REAL_TIME') { return $false }
    }
    return $true
}

<#
    Power and thermal deltas, with "unreadable" kept distinct from "zero". A device that refuses to report its
    temperature yields `measured=$false` plus a note - never a fabricated number.
#>
function Test-BatteryAndThermal {
    param([hashtable]$Before, [hashtable]$After)
    $notes = @()
    $levelDelta = $null
    if ($null -ne $Before.level -and $null -ne $After.level) {
        $levelDelta = [int]$After.level - [int]$Before.level
    } else {
        $notes += 'battery level unreadable; NOT MEASURED'
    }
    $temperatureDeltaC = $null
    $measured = $false
    if ($null -eq $Before.temperatureC -or $null -eq $After.temperatureC) {
        $notes += 'temperature unreadable from dumpsys on this device; NOT MEASURED'
    } else {
        $temperatureDeltaC = [math]::Round(([double]$After.temperatureC - [double]$Before.temperatureC), 3)
        $measured = $true
        $notes += "temperature measured from dumpsys"
    }
    return @{
        measured          = $measured
        levelDelta        = $levelDelta
        temperatureDeltaC = $temperatureDeltaC
        notes             = ($notes -join '; ')
    }
}

# --------------------------------------------------------------------------- device

<#
    Every adb call goes through here. The project's recurring PowerShell 5.1 trap: with `$ErrorActionPreference = 'Stop'`
    a native command's **stderr** becomes a terminating error, and `adb` uses stderr for ordinary notices
    (`su: inaccessible or not found`, `JAVA_TOOL_OPTIONS`). An expected, informative line must not abort a 30-minute
    soak halfway through.
#>
function Invoke-AdbRaw {
    param([string]$AdbPath, [string[]]$Arguments)
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

function Get-BatteryReading {
    param([string]$AdbPath, [string]$Serial)
    $level = $null
    $temperatureC = $null
    $battery = (Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'dumpsys battery')).stdout
    if ($battery -match 'level:\s*(\d+)') { $level = [int]$Matches[1] }
    if ($battery -match 'temperature:\s*(-?\d+)') { $temperatureC = [int]$Matches[1] / 10.0 }
    if ($null -eq $temperatureC) {
        $thermal = (Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Serial, 'shell', 'dumpsys thermalservice')).stdout
        if ($thermal -match 'Temperature\{mValue=([0-9.]+)') { $temperatureC = [double]$Matches[1] }
    }
    return @{ level = $level; temperatureC = $temperatureC }
}

function Invoke-SoakUplink {
    param(
        [int]$Minutes = 30,
        [int]$SampleSeconds = 60,
        [string]$LogDir,
        [string]$AdbPath,
        [string]$Glasses,
        [string]$Phone
    )
    if (-not (Test-Path $AdbPath)) { throw "adb not found at $AdbPath" }
    [IO.Directory]::CreateDirectory($LogDir) | Out-Null

    $windows = [math]::Max(1, [int][math]::Floor($Minutes * 60 / $SampleSeconds))
    Write-Host "P3_T2_START windows=$windows sampleSeconds=$SampleSeconds glasses=$Glasses phone=$Phone"
    Write-Host "P3_T2_REMINDER the phone must be awake and unlocked before this run; K40/K70 Dozing breaks instrumentation"

    # The phone-side audio callback is a single slot, first-come-first-served (P2): the vendor sample would hold it.
    Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Phone, 'shell', 'am', 'force-stop', 'com.rokid.renewcxrlsample') | Out-Null

    $before = Get-BatteryReading -AdbPath $AdbPath -Serial $Phone
    Write-Host "P3_T2_BATTERY before level=$($before.level) temperatureC=$($before.temperatureC)"

    $all = @()
    $lockInjected = $false
    for ($window = 1; $window -le $windows; $window++) {
        Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Glasses, 'logcat', '-c') | Out-Null
        # Force-stop first, then cold-start. The probe Activity is usually already the top-most instance, and driving it
        # with `am start` on a warm instance delivered the intent to `onNewIntent` without the uplink branch ever running:
        # both smoke windows reported NO_REPORT while the same apk worked perfectly from a cold start
        # (PROBE_UPLINK ... attached=true connected=true refusal=none). That is a harness defect, not a device limit, and
        # the P2 lesson is precisely that a self-inflicted negative must never be recorded as a vendor finding.
        Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Glasses, 'shell', 'am', 'force-stop', 'edith.glasses.probe') | Out-Null
        Start-Sleep -Seconds 2
        Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Glasses, 'shell', 'am', 'start', '-n',
            'edith.glasses.probe/.ProbeActivity', '--ez', 'uplink', 'true', '--ei', 'uplinkSeconds', "$SampleSeconds") | Out-Null
        Start-Sleep -Seconds ($SampleSeconds + 5)

        $lines = (Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Glasses, 'logcat', '-d', '-s', 'EdithAudioProbe:*')).stdout -split "`r?`n"
        $windowSamples = Convert-ProbeLinesToSamples -Lines $lines -SampleSeconds $SampleSeconds
        foreach ($sample in $windowSamples) {
            $sample.minute = $all.Count + 1
            $all += $sample
            Write-Host ("P3_T2_WINDOW minute={0} payloadBytes={1} verdict={2} started={3}" -f `
                $sample.minute, $sample.payloadBytes, $sample.verdict, $sample.started)
        }
        if ($windowSamples.Count -eq 0) {
            # No sample at all means the window produced no report: recorded, not silently skipped.
            $all += @{ minute = $all.Count + 1; payloadBytes = 0; deltaBytes = 0; verdict = 'NO_REPORT'; started = $false }
            Write-Host ("P3_T2_WINDOW minute={0} NO_REPORT" -f $all.Count)
        }

        # One screen lock inside the window, so the soak covers the real background/locked case rather than a phone
        # that happened to stay awake for the whole run.
        if (-not $lockInjected -and $window -ge [math]::Ceiling($windows / 2)) {
            Invoke-AdbRaw -AdbPath $AdbPath -Arguments @('-s', $Phone, 'shell', 'input', 'keyevent', 'KEYCODE_POWER') | Out-Null
            $lockInjected = $true
            Write-Host "P3_T2_SCREEN_LOCK injected at minute=$($all.Count)"
        }
    }

    $after = Get-BatteryReading -AdbPath $AdbPath -Serial $Phone
    Write-Host "P3_T2_BATTERY after level=$($after.level) temperatureC=$($after.temperatureC)"

    $thermal = Test-BatteryAndThermal -Before $before -After $after
    $ok = Test-MonotonicGrowth -PerMinute $all

    $report = @{
        schemaVersion = 1
        minutes       = $Minutes
        sampleSeconds = $SampleSeconds
        glasses       = $Glasses
        phone         = $Phone
        screenLocked  = $lockInjected
        perMinute     = $all
        battery       = @{ before = $before; after = $after }
        thermal       = $thermal
        ok            = $ok
    }
    $reportPath = Join-Path $LogDir "soak-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')).json"
    $report | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
    Write-Host "P3_T2_VERDICT ok=$ok windows=$($all.Count) thermalMeasured=$($thermal.measured) notes=$($thermal.notes)"
    Write-Host "P3_T2_REPORT $reportPath"
    return $report
}

if ($Run) {
    Invoke-SoakUplink -Minutes $Minutes -SampleSeconds $SampleSeconds -LogDir $LogDir `
        -AdbPath $Adb -Glasses $GlassesSerial -Phone $PhoneSerial | Out-Null
}
