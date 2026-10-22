$ErrorActionPreference = 'Stop'

# P3.0 T2 harness self-test. Dot-sourcing `soak-uplink.ps1` is inert: its device loop runs only under `-Run`, so the
# parsing and verdict functions can be exercised here without a phone, a glasses or a 30-minute wait.
#
# The functions under test are the ones that decide whether a soak passed. A soak whose numbers were mis-parsed, or
# whose zero-byte window was averaged away, is worse than no soak: it produces a confident wrong answer.
. (Join-Path $PSScriptRoot 'soak-uplink.ps1')

$script:passed = 0
$script:failed = 0

function Check {
    param([string]$Name, [scriptblock]$Assert)
    try {
        & $Assert
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        Write-Output "FAIL $Name : $($_.Exception.Message)"
    }
}

$startLine = 'PROBE_UPLINK_START seconds=60 sendVariant=capsWithDataArray permission=true'
# Captured from the device: UPLINK mode reports `bytes=` / `framesSent=` / `refusal=`, NOT `payloadBytes=` (that field
# belongs to the separate THROUGHPUT mode). The first parser looked for the wrong field, so every window matched nothing
# and a 30-minute soak would have reported NO_REPORT throughout while the device was working perfectly.
$windowLine = 'PROBE_UPLINK uplink sendVariant=capsWithDataArray attached=true connected=true seconds=60 started=true blocks=600 bytes=384000 framesSent=600 framesDropped=0 channels=8 sampleRate=16000 refusal=none lastCode=0 endReason=SourceEnded'
$refusedLine = 'PROBE_UPLINK uplink sendVariant=capsWithDataArray attached=true connected=true seconds=60 started=true blocks=10 bytes=6400 framesSent=10 framesDropped=590 channels=8 sampleRate=16000 refusal=refused lastCode=-1 endReason=SourceEnded'
$noiseLine = 'PROBE_REPORT_FILE /sdcard/probe.json'

Check 'parse-extracts-one-window-sample' {
    $samples = Convert-ProbeLinesToSamples -Lines @($startLine, $noiseLine, $windowLine) -SampleSeconds 60
    if ($samples.Count -ne 1) { throw "expected 1 sample, got $($samples.Count)" }
    if ($samples[0].minute -ne 1) { throw "minute $($samples[0].minute)" }
    if ($samples[0].payloadBytes -ne 384000) { throw "payloadBytes $($samples[0].payloadBytes)" }
    if ($samples[0].deltaBytes -ne 384000) { throw "deltaBytes $($samples[0].deltaBytes)" }
    if ($samples[0].verdict -ne 'SUSTAINS_REAL_TIME') { throw "verdict $($samples[0].verdict)" }
    if ($samples[0].started -ne $true) { throw 'a sample without its START line must not be trusted as a window' }
}

Check 'parse-ignores-lines-without-a-payload-count' {
    $samples = Convert-ProbeLinesToSamples -Lines @($startLine, $noiseLine) -SampleSeconds 60
    if ($samples.Count -ne 0) { throw "expected 0 samples, got $($samples.Count)" }
}

Check 'parse-marks-a-window-with-no-start-line-as-unstarted' {
    $samples = Convert-ProbeLinesToSamples -Lines @($windowLine) -SampleSeconds 60
    if ($samples.Count -ne 1) { throw "expected 1 sample, got $($samples.Count)" }
    if ($samples[0].started -ne $false) { throw 'a report without a START is a void window and must be labelled' }
}

Check 'monotonic-passes-when-every-window-carried-bytes' {
    $samples = Convert-ProbeLinesToSamples -Lines @($startLine, $windowLine, $startLine, $windowLine) -SampleSeconds 60
    if (-not (Test-MonotonicGrowth -PerMinute $samples)) { throw 'two healthy windows must pass' }
}

Check 'monotonic-fails-on-a-zero-growth-window' {
    $zeroLine = 'PROBE_UPLINK accepted=0/600 payloadBytes=0 elapsedMillis=12000 achieved=0KB/s required=32KB/s refusalRatio=100% verdict=NO_FRAMES_ATTEMPTED'
    $samples = Convert-ProbeLinesToSamples -Lines @($startLine, $windowLine, $startLine, $zeroLine) -SampleSeconds 60
    if (Test-MonotonicGrowth -PerMinute $samples) { throw 'a zero-byte window must fail the soak, not be averaged away' }
}

Check 'monotonic-fails-when-the-transport-refused-frames' {
    $samples = Convert-ProbeLinesToSamples -Lines @($startLine, $refusedLine) -SampleSeconds 60
    if (Test-MonotonicGrowth -PerMinute $samples) { throw 'bytes arriving while frames are refused is not a pass' }
}

Check 'monotonic-fails-on-an-unstarted-window' {
    $samples = Convert-ProbeLinesToSamples -Lines @($windowLine) -SampleSeconds 60
    if (Test-MonotonicGrowth -PerMinute $samples) { throw 'a window whose START was never logged is a void window' }
}

Check 'monotonic-fails-on-empty-input' {
    if (Test-MonotonicGrowth -PerMinute @()) { throw 'an empty soak is not a pass' }
}

Check 'battery-reports-not-measured-without-a-temperature-reading' {
    $before = @{ level = 80; temperatureC = $null }
    $after = @{ level = 71; temperatureC = $null }
    $result = Test-BatteryAndThermal -Before $before -After $after
    if ($result.measured -ne $false) { throw 'a missing temperature must be reported as not measured' }
    if ([string]::IsNullOrWhiteSpace($result.notes)) { throw 'not-measured must come with a note saying what was missing' }
    if ($result.levelDelta -ne -9) { throw "levelDelta $($result.levelDelta)" }
}

Check 'battery-reports-measured-when-both-readings-exist' {
    $before = @{ level = 80; temperatureC = 31.0 }
    $after = @{ level = 71; temperatureC = 37.5 }
    $result = Test-BatteryAndThermal -Before $before -After $after
    if ($result.measured -ne $true) { throw 'both readings present must be measured' }
    if ([math]::Abs($result.temperatureDeltaC - 6.5) -gt 0.001) { throw "temperatureDeltaC $($result.temperatureDeltaC)" }
}

Write-Output "SOAK_UPLINK_TESTS passed=$script:passed failed=$script:failed"
if ($script:failed -gt 0) { exit 1 }
