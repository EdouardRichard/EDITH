param([Parameter(Mandatory)][string]$MetricsPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Assert-Condition([bool]$Condition) { if (-not $Condition) { throw 'INVALID_METRICS' } }
function Test-Number($Value) {
    return ($Value -is [int] -or $Value -is [long] -or $Value -is [double] -or $Value -is [decimal]) -and
        -not [double]::IsNaN([double]$Value) -and -not [double]::IsInfinity([double]$Value)
}
$result = [ordered]@{ schemaVersion=1; selected=$false; complete=$false; failures=@(); rtfP95=$null;
    backlogGrowing=$null; semanticCorrect=0; semanticTotal=0; keySemanticAccuracy=$null; measuredWindows=0;
    backlogRule='second-half OLS slope <= 0, non-increasing endpoints, final queue zero, zero faults/drops';
    rtfConvention='combined VAD+ASR+speaker processing work / unique source audio seconds; replay waits excluded' }
try {
    Assert-Condition ((Get-Item -LiteralPath $MetricsPath).Length -le 1MB)
    $data = Get-Content -LiteralPath $MetricsPath -Raw | ConvertFrom-Json
    Assert-Condition ($data.schemaVersion -eq 1 -and $data.corpusId -ceq 'p2-fixed-zh-30-v1')
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'engine-manifest.json') -Raw | ConvertFrom-Json
    $candidate = $data.candidate
    Assert-Condition ($candidate.deviceId -is [string] -and $candidate.deviceId -cmatch '^[A-Za-z0-9_-]{1,64}$')
    Assert-Condition ($candidate.runtimeSha256 -is [string] -and $candidate.runtimeSha256 -match '^[A-Fa-f0-9]{64}$' -and
        $candidate.runtimeSha256 -eq $manifest.runtime.sha256)
    Assert-Condition ((Test-Number $candidate.sampleRateHz) -and $candidate.sampleRateHz -eq 16000 -and
        (Test-Number $candidate.frameMillis) -and $candidate.frameMillis -eq 20 -and $candidate.provider -ceq 'cpu' -and
        (Test-Number $candidate.threads) -and $candidate.threads -in @(1,2,4) -and
        (Test-Number $candidate.endpointMillis) -and $candidate.endpointMillis -in @(250,350,450))
    $modelNames = @($candidate.modelHashes.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @($manifest.artifacts.role | Sort-Object)
    Assert-Condition (($modelNames -join ',') -ceq ($expectedNames -join ','))
    foreach ($artifact in $manifest.artifacts) {
        $hash = $candidate.modelHashes.($artifact.role)
        Assert-Condition ($hash -is [string] -and $hash -match '^[A-Fa-f0-9]{64}$' -and $hash -eq $artifact.sha256)
    }
    $result.candidate = [ordered]@{ deviceId=$candidate.deviceId; runtimeVersion=$manifest.runtimeVersion;
        runtimeSha256=$manifest.runtime.sha256; modelHashes=$candidate.modelHashes; provider=$candidate.provider;
        threads=$candidate.threads; endpointMillis=$candidate.endpointMillis; frameMillis=$candidate.frameMillis;
        sampleRateHz=$candidate.sampleRateHz; distributionReview=$manifest.runtime.distributionReview }
    Assert-Condition (($data.nativeExecuted -is [bool]) -and ($data.nativeLifecyclePassed -is [bool]) -and ($data.interrupted -is [bool]))
    Assert-Condition ((Test-Number $data.warmupSeconds) -and (Test-Number $data.measuredSeconds))
    $windows = @($data.windows)
    Assert-Condition ($windows.Count -gt 0 -and $windows.Count -le 10000)
    $rtfs = @(); $queue = @(); $audio = 0.0; $speakerCalls = 0; $faults = 0; $drops = 0
    for ($index = 0; $index -lt $windows.Count; $index++) {
        $item = $windows[$index]
        Assert-Condition ($item.index -eq $index + 1)
        Assert-Condition ((Test-Number $item.audioSeconds) -and $item.audioSeconds -eq 10)
        Assert-Condition ((Test-Number $item.rtf) -and $item.rtf -ge 0)
        foreach ($field in @('queueDepth','speakerCalls','faults','droppedFrames')) {
            $value = $item.$field
            Assert-Condition ((Test-Number $value) -and $value -ge 0 -and $value -le 1000000000 -and $value -eq [math]::Floor($value))
        }
        $audio += $item.audioSeconds; $rtfs += [double]$item.rtf; $queue += [double]$item.queueDepth
        $speakerCalls += $item.speakerCalls; $faults += $item.faults; $drops += $item.droppedFrames
    }
    Assert-Condition ([math]::Abs($audio - $data.measuredSeconds) -lt 0.000001)
    $sorted = @($rtfs | Sort-Object)
    $result.rtfP95 = $sorted[[int][math]::Ceiling(0.95 * $sorted.Count) - 1]
    $result.measuredWindows = $windows.Count
    $half = $queue[[int][math]::Floor($queue.Count / 2)..($queue.Count - 1)]
    $sumX = 0.0; $sumY = 0.0; $sumXY = 0.0; $sumXX = 0.0
    for ($index = 0; $index -lt $half.Count; $index++) {
        $sumX += $index; $sumY += $half[$index]; $sumXY += $index * $half[$index]; $sumXX += $index * $index
    }
    $denominator = $half.Count * $sumXX - $sumX * $sumX
    $slope = if ($denominator -gt 0) { ($half.Count * $sumXY - $sumX * $sumY) / $denominator } else { 0.0 }
    $result.backlogGrowing = ($slope -gt 0 -or $half[-1] -gt $half[0] -or $queue[-1] -gt 0)
    $result.queueSlope = $slope; $result.queueFirst = $queue[0]; $result.queueLast = $queue[-1]; $result.queueMax = ($queue | Measure-Object -Maximum).Maximum
    $trials = @($data.semanticTrials)
    Assert-Condition ($trials.Count -le 30)
    $ids = @{}
    foreach ($trial in $trials) {
        Assert-Condition ($trial.id -is [string] -and $trial.id -cmatch '^P2Q(00[1-9]|0[12][0-9]|030)$' -and $trial.correct -is [bool])
        Assert-Condition (-not $ids.ContainsKey($trial.id))
        $ids[$trial.id] = $true
        if ($trial.correct) { $result.semanticCorrect++ }
    }
    $result.semanticTotal = $trials.Count
    if ($trials.Count) { $result.keySemanticAccuracy = $result.semanticCorrect / [double]$trials.Count }
    if ($data.warmupSeconds -lt 60 -or $data.measuredSeconds -lt 600 -or $windows.Count -lt 60) { $result.failures += 'INSUFFICIENT_DURATION' }
    if ($trials.Count -ne 30) { $result.failures += 'INSUFFICIENT_SEMANTIC_TRIALS' }
    if (-not $data.nativeExecuted -or -not $data.nativeLifecyclePassed -or $data.interrupted -or $speakerCalls -eq 0) { $result.failures += 'INCOMPLETE_COMBINED_NATIVE_RUN' }
    if ($faults -gt 0 -or $drops -gt 0) { $result.failures += 'LOST_OR_FAULTED_WORK' }
    $result.complete = $result.failures.Count -eq 0
    if ($result.rtfP95 -gt 0.7) { $result.failures += 'RTF_P95_FAILED' }
    if ($result.backlogGrowing) { $result.failures += 'BACKLOG_FAILED' }
    if ($null -eq $result.keySemanticAccuracy -or $result.keySemanticAccuracy -lt 0.9) { $result.failures += 'SEMANTIC_ACCURACY_FAILED' }
    $result.selected = $result.complete -and $result.failures.Count -eq 0
} catch {
    $result.complete = $false; $result.selected = $false; $result.failures += 'INVALID_METRICS'
}
$result | ConvertTo-Json -Depth 6
