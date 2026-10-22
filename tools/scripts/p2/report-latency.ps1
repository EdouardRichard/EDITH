param([Parameter(Mandatory)][string]$LatencyPath)
# P2 latency reporter (T19). Consumes a PhasedEvidence-shaped run document and reports, per metric:
# sample count, nearest-rank P50/P95, the R3 text targets, failure/cancellation/incomplete counts that STAY
# IN THE DENOMINATOR, and the precision limitation of manual observation.
#
# The one rule this script exists to enforce: absolute times from different device clocks are never
# subtracted. A sample declares the clock it came from, because only a T18 mapping interval makes a
# phone-minus-glasses subtraction meaningful, and an uncalibrated pair has no interval at all. Anything that
# crosses clocks is refused and counted, never reported as a latency.
#
# Every string here is ASCII: this file is invoked by both Windows PowerShell 5.1 and pwsh, and a legacy code
# page would otherwise mangle non-ASCII text, as it already did to two helper scripts earlier in this phase.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# The whitelist of reportable timing segments. Anything else is rejected rather than reported.
$allowedMetrics = @('firstToken', 'firstReadableHint', 'displayAck', 'humanVisible')
# The whitelist of trace marks this reporter can count. Mirrors TraceRecorder.TraceMark.
$allowedMarks = @('AUDIO_RECEIVED', 'SPEECH_END', 'ASR_PARTIAL', 'ASR_FINAL', 'SPEAKER_READY',
    'MANUAL_DECISION', 'REQUEST_START', 'FIRST_TOKEN', 'FIRST_READABLE_HINT', 'DISPLAY_ACK')
# Declared clock domains. `crossDevice` exists only so it can be refused explicitly and counted.
$allowedSources = @('phone', 'glass', 'crossDevice')
$allowedMethods = @('wearerConfirmation', 'highPrecisionVideo')
$allowedFailureKinds = @('failed', 'cancelled', 'incomplete', 'crossDevice')

$result = [ordered]@{
    schemaVersion = 1
    parsed = $false
    runId = $null
    rejectedTraces = 0
    rejectedSamples = 0
    trace = [ordered]@{}
    metrics = [ordered]@{}
    glassOnly = [ordered]@{}
    refused = [ordered]@{ crossDeviceClockSamples = 0; crossDeviceReasons = @() }
    failures = [ordered]@{ failed = 0; cancelled = 0; incomplete = 0; sampleCount = 0; totalAttempts = 0
        successRate = $null; reasons = @() }
    manualObservation = [ordered]@{ wearerConfirmation = 0; highPrecisionVideo = 0; unlabelled = 0
        precisionLimited = $false }
    r3Targets = [ordered]@{ evaluated = $false; p50TargetMillis = 1500; p95TargetMillis = 3000 }
}

function Get-Field {
    # StrictMode makes a missing property throw, but these documents legitimately omit optional fields.
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function ConvertTo-CamelCase([string]$Name) {
    $parts = $Name.ToLowerInvariant().Split('_')
    $builder = New-Object System.Text.StringBuilder
    foreach ($part in $parts) {
        if ($part.Length -eq 0) { continue }
        if ($builder.Length -eq 0) { [void]$builder.Append($part) }
        else { [void]$builder.Append($part.Substring(0, 1).ToUpperInvariant()).Append($part.Substring(1)) }
    }
    return $builder.ToString()
}

function Get-NearestRank([double[]]$Sorted, [double]$Percentile) {    if ($Sorted.Count -eq 0) { return $null }
    $rank = [int][math]::Ceiling($Percentile * $Sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    if ($rank -ge $Sorted.Count) { $rank = $Sorted.Count - 1 }
    return $Sorted[$rank]
}

try {
    if (-not (Test-Path -LiteralPath $LatencyPath -PathType Leaf)) { throw 'Missing latency document' }
    if ((Get-Item -LiteralPath $LatencyPath).Length -gt 4MB) { throw 'Latency document too large' }
    $data = Get-Content -LiteralPath $LatencyPath -Raw | ConvertFrom-Json
    if ((Get-Field $data 'schemaVersion') -ne 1) { throw 'Unsupported schemaVersion' }
    $result.runId = Get-Field $data 'runId'

    # --- trace counts ---------------------------------------------------------------------------------
    # Keys are lowerCamelCase deliberately: Windows PowerShell resolves PSCustomObject properties
    # case-sensitively, so a report key that differs from the contract name only in case reads as absent.
    $markCounts = [ordered]@{}
    $markKeys = @{}
    foreach ($mark in $allowedMarks) {
        $key = ConvertTo-CamelCase $mark
        $markKeys[$mark] = $key
        $markCounts[$key] = 0
    }
    foreach ($trace in @(Get-Field $data 'traces')) {
        if ($null -eq $trace) { continue }
        $mark = [string](Get-Field $trace 'mark')
        if ($allowedMarks -notcontains $mark) { $result.rejectedTraces++; continue }
        $markCounts[$markKeys[$mark]] = $markCounts[$markKeys[$mark]] + 1
    }
    $result.trace = $markCounts

    # --- latency samples ------------------------------------------------------------------------------
    $phone = @{}
    $glass = @{}
    $manual = @{ wearerConfirmation = 0; highPrecisionVideo = 0; unlabelled = 0 }
    foreach ($sample in @(Get-Field $data 'samples')) {
        if ($null -eq $sample) { continue }
        $metric = [string](Get-Field $sample 'metric')
        $source = [string](Get-Field $sample 'source')
        if ($allowedSources -notcontains $source) { $result.rejectedSamples++; continue }
        if ($source -eq 'crossDevice') {
            # A phone-minus-glasses value is only meaningful inside a T18 mapping interval, and this document
            # carries no interval, so it is refused rather than reported. Checked before anything else: the
            # refusal is about the clock, so it must be counted whatever the segment is called.
            $result.refused.crossDeviceClockSamples++
            continue
        }
        $millis = [double](Get-Field $sample 'millis')
        if ([double]::IsNaN($millis) -or [double]::IsInfinity($millis) -or $millis -lt 0) {
            $result.rejectedSamples++; continue
        }
        if ($source -eq 'glass') {
            # Reported separately and never compared with a phone instant, so the segment name is
            # diagnostic rather than part of the reported contract; the whitelist does not apply here.
            if ($metric -isNot [string] -or [string]::IsNullOrWhiteSpace($metric)) {
                $result.rejectedSamples++; continue
            }
            if (-not $glass.ContainsKey($metric)) { $glass[$metric] = New-Object System.Collections.Generic.List[double] }
            $glass[$metric].Add($millis)
            continue
        }
        # Only the phone domain feeds the reported percentiles, so only there is the whitelist binding.
        if ($allowedMetrics -notcontains $metric) { $result.rejectedSamples++; continue }
        if (-not $phone.ContainsKey($metric)) { $phone[$metric] = New-Object System.Collections.Generic.List[double] }
        $phone[$metric].Add($millis)
        if ($metric -eq 'humanVisible') {
            $method = [string](Get-Field $sample 'method')
            if ($allowedMethods -contains $method) { $manual[$method] = $manual[$method] + 1 }
            else { $manual.unlabelled = $manual.unlabelled + 1 }
        }
    }

    foreach ($metric in $allowedMetrics) {
        if (-not $phone.ContainsKey($metric)) { continue }
        $sorted = @($phone[$metric] | Sort-Object)
        $result.metrics[$metric] = [ordered]@{
            sampleCount = $sorted.Count
            p50Millis = Get-NearestRank $sorted 0.50
            p95Millis = Get-NearestRank $sorted 0.95
            minMillis = $sorted[0]
            maxMillis = $sorted[$sorted.Count - 1]
            clockSource = 'phone'
        }
    }
    foreach ($metric in @($glass.Keys | Sort-Object)) {
        $key = [string]$metric
        $sorted = @($glass[$metric] | Sort-Object)
        $result.glassOnly[$key] = [ordered]@{
            sampleCount = $sorted.Count
            p50Millis = Get-NearestRank $sorted 0.50
            p95Millis = Get-NearestRank $sorted 0.95
            clockSource = 'glass'
            # Reported for completeness only: a glass instant is not comparable to a phone instant.
            comparableToPhone = $false
        }
    }

    # --- failures stay in the denominator --------------------------------------------------------------
    foreach ($failure in @(Get-Field $data 'failures')) {
        if ($null -eq $failure) { continue }
        $kind = [string](Get-Field $failure 'kind')
        if ($allowedFailureKinds -notcontains $kind) { continue }
        if ($kind -eq 'crossDevice') {
            $result.refused.crossDeviceClockSamples++
            $result.refused.crossDeviceReasons += [string](Get-Field $failure 'reason')
            continue
        }
        $result.failures[$kind] = $result.failures[$kind] + 1
        $result.failures.reasons += [string](Get-Field $failure 'reason')
    }
    $sampleCount = 0
    foreach ($metric in $allowedMetrics) { if ($phone.ContainsKey($metric)) { $sampleCount += $phone[$metric].Count } }
    $result.failures.sampleCount = $sampleCount
    $result.failures.totalAttempts = $sampleCount + $result.failures.failed +
        $result.failures.cancelled + $result.failures.incomplete
    if ($result.failures.totalAttempts -gt 0) {
        $result.failures.successRate = [math]::Round($sampleCount / [double]$result.failures.totalAttempts, 4)
    }

    # --- manual observation precision -------------------------------------------------------------------
    $result.manualObservation.wearerConfirmation = $manual.wearerConfirmation
    $result.manualObservation.highPrecisionVideo = $manual.highPrecisionVideo
    $result.manualObservation.unlabelled = $manual.unlabelled
    # Wearer confirmation is human reaction time, not a measurement: without video timing the report must say
    # so instead of implying millisecond precision.
    $result.manualObservation.precisionLimited = ($manual.highPrecisionVideo -eq 0)

    # --- R3 text targets --------------------------------------------------------------------------------
    if ($result.metrics.Contains('humanVisible')) {
        $visible = $result.metrics['humanVisible']
        $result.r3Targets.evaluated = $true
        $result.r3Targets.humanVisible = [ordered]@{
            p50Pass = ($visible.p50Millis -le $result.r3Targets.p50TargetMillis)
            p95Pass = ($visible.p95Millis -le $result.r3Targets.p95TargetMillis)
            p50Millis = $visible.p50Millis
            p95Millis = $visible.p95Millis
        }
    }
    $result.parsed = $true
} catch {
    $result.parsed = $false
    $result.error = $_.Exception.Message
}
$result | ConvertTo-Json -Depth 6
