param([string]$OutputDirectory = "$PSScriptRoot/../../../.local/p2-latency-report-tests")
$ErrorActionPreference = 'Stop'
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$reporter = Join-Path $PSScriptRoot 'report-latency.ps1'

function New-Run {
    param(
        [string[]]$Marks = @('REQUEST_START', 'FIRST_TOKEN', 'FIRST_READABLE_HINT', 'DISPLAY_ACK'),
        [object[]]$Samples = $null,
        [object[]]$Failures = $null
    )
    $traces = @()
    $session = 's-11111111-2222-3333-4444-555555555555'
    $index = 0
    foreach ($mark in $Marks) {
        $index++
        $requestId = if ($mark -eq 'REQUEST_START') { 'r-11111111-2222-3333-4444-555555555555' } else { $null }
        $traces += @{ sessionId = $session; requestId = $requestId; mark = $mark; timeNanos = $index * 1000000
            safeCode = $null; queuedBytes = $null; versionHash = $null }
    }
    if ($null -eq $Samples) {
        $Samples = @(
            @{ metric = 'firstToken'; millis = 400; source = 'phone' },
            @{ metric = 'firstToken'; millis = 600; source = 'phone' },
            @{ metric = 'firstReadableHint'; millis = 900; source = 'phone' },
            @{ metric = 'firstReadableHint'; millis = 1200; source = 'phone' },
            @{ metric = 'displayAck'; millis = 1000; source = 'phone' },
            @{ metric = 'humanVisible'; millis = 1400; source = 'phone' }
        )
    }
    return @{
        schemaVersion = 1
        runId = 'synthetic-latency-run'
        traces = $traces
        samples = $Samples
        failures = if ($null -eq $Failures) { @() } else { $Failures }
    }
}

$script:passed = 0
$script:failed = 0
function Check-Report {
    param([string]$Name, [hashtable]$Run, [scriptblock]$Assert)
    try {
        $path = Join-Path $OutputDirectory "$Name.json"
        $Run | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8
        $raw = & $reporter -LatencyPath $path
        # Bind the scalar document explicitly: letting the assertion scriptblock's own output merge into the
        # pipeline would turn $r into an array, and `-not @($false)` is false, which silently inverts checks.
        $result = $raw | ConvertFrom-Json
        $null = & $Assert $result
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        Write-Output "FAIL $Name : $($_.Exception.Message)"
        $position = $_.InvocationInfo.PositionMessage
        if ($position) { Write-Output ("       at " + ($position -replace '\s+', ' ')) }
    }
}

Check-Report 'percentiles' (New-Run) {
    param($r)
    # nearest-rank over 2 samples at 400 and 600 ms: P50 is the 1st, P95 is the 2nd.
    if ($r.metrics.firstToken.sampleCount -ne 2) { throw "sampleCount $($r.metrics.firstToken.sampleCount)" }
    if ($r.metrics.firstToken.p50Millis -ne 400) { throw "P50 $($r.metrics.firstToken.p50Millis)" }
    if ($r.metrics.firstToken.p95Millis -ne 600) { throw "P95 $($r.metrics.firstToken.p95Millis)" }
    if ($r.metrics.firstReadableHint.p95Millis -ne 1200) { throw "hint P95 $($r.metrics.firstReadableHint.p95Millis)" }
}

Check-Report 'r3-targets' (New-Run -Samples @(
        @{ metric = 'humanVisible'; millis = 1400; source = 'phone' },
        @{ metric = 'humanVisible'; millis = 2900; source = 'phone' }
    )) {
    param($r)
    if ($r.r3Targets.humanVisible.p50Pass -ne $true) { throw 'P50 should pass at 1400 ms (target 1500)' }
    if ($r.r3Targets.humanVisible.p95Pass -ne $true) { throw 'P95 should pass at 2900 ms (target 3000)' }
}

Check-Report 'r3-target-miss' (New-Run -Samples @(
        @{ metric = 'humanVisible'; millis = 1600; source = 'phone' },
        @{ metric = 'humanVisible'; millis = 3200; source = 'phone' }
    )) {
    param($r)
    if ($r.r3Targets.humanVisible.p50Pass -ne $false) { throw 'P50 should fail at 1600 ms' }
    if ($r.r3Targets.humanVisible.p95Pass -ne $false) { throw 'P95 should fail at 3200 ms' }
}

Check-Report 'cross-device-is-refused' (New-Run -Samples @(
        @{ metric = 'firstReadableHint'; millis = 900; source = 'phone' },
        @{ metric = 'glassToPhone'; millis = 800; source = 'glass' },
        @{ metric = 'mixed'; millis = 700; source = 'crossDevice' }
    )) {
    param($r)
    if ($r.refused.crossDeviceClockSamples -ne 1) { throw "expected 1 refused sample, got $($r.refused.crossDeviceClockSamples)" }
    # A phone-minus-glass subtraction must not appear as a reported phone metric.
    if ($r.metrics.PSObject.Properties.Name -contains 'mixed') { throw 'a cross-device delta was reported as a metric' }
    # Glass-only samples are reported separately and never merged into a phone percentile. Only glass-sourced
    # samples appear here, so the refusals above are absent by construction.
    if (-not $r.glassOnly.glassToPhone) { throw 'glass-sourced samples must be reported separately' }
    if ($r.glassOnly.glassToPhone.comparableToPhone -ne $false) {
        throw 'a glass instant must not be marked comparable to a phone instant'
    }
    if ($r.glassOnly.PSObject.Properties.Name -contains 'mixed') {
        throw 'a refused cross-device sample must not appear anywhere'
    }
}

Check-Report 'uncalibrated-interval-is-refused' (New-Run -Samples @(
        @{ metric = 'firstReadableHint'; millis = 900; source = 'phone' }
    ) -Failures @(
        @{ kind = 'crossDevice'; reason = 'no clock mapping'; detail = 'syncId missing' }
    )) {
    param($r)
    if ($r.refused.crossDeviceClockSamples -lt 1) { throw 'a declared cross-device failure must be counted as refused' }
}

Check-Report 'trace-counts' (New-Run -Marks @('REQUEST_START', 'REQUEST_START', 'FIRST_TOKEN') -Samples @(
        @{ metric = 'firstToken'; millis = 400; source = 'phone' }
    )) {
    param($r)
    if ($r.trace.requestStart -ne 2) { throw "requestStart $($r.trace.requestStart)" }
    if ($r.trace.firstToken -ne 1) { throw "firstToken $($r.trace.firstToken)" }
    if ($r.trace.displayAck -ne 0) { throw "displayAck $($r.trace.displayAck)" }
}

Check-Report 'failures-stay-in-the-denominator' (New-Run -Samples @(
        @{ metric = 'firstToken'; millis = 400; source = 'phone' },
        @{ metric = 'firstToken'; millis = 600; source = 'phone' }
    ) -Failures @(
        @{ kind = 'failed'; reason = 'MODEL_FAILED'; detail = 'HTTP 500' },
        @{ kind = 'cancelled'; reason = 'MODEL_CANCELLED'; detail = 'user pause' },
        @{ kind = 'incomplete'; reason = 'MODEL_INCOMPLETE'; detail = 'stream ended early' }
    )) {
    param($r)
    if ($r.failures.failed -ne 1) { throw "failed $($r.failures.failed)" }
    if ($r.failures.cancelled -ne 1) { throw "cancelled $($r.failures.cancelled)" }
    if ($r.failures.incomplete -ne 1) { throw "incomplete $($r.failures.incomplete)" }
    if ($r.failures.totalAttempts -ne 5) { throw "totalAttempts $($r.failures.totalAttempts): failures must stay in the denominator" }
    if ($r.failures.successRate -ne 0.4) { throw "successRate $($r.failures.successRate)" }
}

Check-Report 'manual-observation-is-labelled' (New-Run -Samples @(
        @{ metric = 'humanVisible'; millis = 1400; source = 'phone'; method = 'wearerConfirmation' },
        @{ metric = 'humanVisible'; millis = 1500; source = 'phone'; method = 'highPrecisionVideo' }
    )) {
    param($r)
    if ($r.manualObservation.wearerConfirmation -ne 1) { throw 'wearer-confirmed samples must be labelled' }
    if ($r.manualObservation.highPrecisionVideo -ne 1) { throw 'video-timed samples must be labelled' }
    # Video timing IS present here, so the report must NOT claim a precision limitation.
    if ($r.manualObservation.precisionLimited -ne $false) {
        throw 'a video-timed run must not be reported as precision limited'
    }
}

Check-Report 'manual-only-is-precision-limited' (New-Run -Samples @(
        @{ metric = 'humanVisible'; millis = 1400; source = 'phone'; method = 'wearerConfirmation' },
        @{ metric = 'humanVisible'; millis = 1500; source = 'phone' }
    )) {
    param($r)
    if ($r.manualObservation.wearerConfirmation -ne 1) { throw 'wearer-confirmed samples must be labelled' }
    if ($r.manualObservation.unlabelled -ne 1) { throw 'an unlabelled observation must be counted, not assumed' }
    # No video timing: the report must state the precision limitation instead of implying milliseconds.
    if ($r.manualObservation.precisionLimited -ne $true) {
        throw 'without video the report must state the precision limitation'
    }
}

Check-Report 'unknown-mark-is-rejected' (New-Run -Marks @('REQUEST_START', 'NOT_A_MARK')) {
    param($r)
    if ($r.rejectedTraces -ne 1) { throw "rejectedTraces $($r.rejectedTraces)" }
}

Check-Report 'unknown-metric-is-rejected' (New-Run -Samples @(
        @{ metric = 'madeUpMetric'; millis = 100; source = 'phone' }
    )) {
    param($r)
    if ($r.rejectedSamples -ne 1) { throw "rejectedSamples $($r.rejectedSamples)" }
    if ($r.metrics.PSObject.Properties.Name -contains 'madeUpMetric') { throw 'an unknown metric was reported' }
}

Check-Report 'empty-input-is-not-a-pass' (New-Run -Marks @() -Samples @()) {
    param($r)
    if ($r.metrics.PSObject.Properties.Name.Count -ne 0) { throw 'no samples means no metrics' }
    if ($r.r3Targets.evaluated -ne $false) { throw 'targets must not be evaluated without samples' }
    if ($r.failures.totalAttempts -ne 0) { throw 'an empty run has no attempts' }
}

Write-Output "LATENCY_REPORT_TESTS passed=$script:passed failed=$script:failed"
if ($script:failed -gt 0) { exit 1 }
