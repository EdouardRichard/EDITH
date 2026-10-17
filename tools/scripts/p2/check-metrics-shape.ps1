param(
    [Parameter(Mandatory)][string]$MetricsPath
)
# Focused parity check: the reporter's own structural validation of an extended EngineMetrics document.
# report-engine.ps1 itself is NOT modified by this session (that edit is outside the T16 software scope),
# so this proves the new resource/identity evidence parses under the same strict rules and that the
# mandatory keys the reporter requires are all still present.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Assert-Condition([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAILED: $Name" }
    Write-Host "  ok: $Name"
}
$result = [ordered]@{ schemaVersion = 1; rows = 0; resources = 0; identity = $false; parsed = $false }
Assert-Condition ((Get-Item -LiteralPath $MetricsPath).Length -le 1MB) 'bounded size'
$data = Get-Content -LiteralPath $MetricsPath -Raw | ConvertFrom-Json
$result.schemaVersion = $data.schemaVersion
Assert-Condition ($data.schemaVersion -eq 1) 'schemaVersion == 1'
Assert-Condition ($data.corpusId -ceq 'p2-fixed-zh-30-v1') 'frozen corpusId'
$candidate = $data.candidate
Assert-Condition ($candidate.deviceId -cmatch '^[A-Za-z0-9_-]{1,64}$') 'deviceId shape'
Assert-Condition ($candidate.runtimeSha256 -match '^[A-Fa-f0-9]{64}$') 'runtimeSha256 shape'
Assert-Condition ($candidate.sampleRateHz -eq 16000 -and $candidate.frameMillis -eq 20) 'frozen geometry'
Assert-Condition ($candidate.provider -ceq 'cpu' -and $candidate.threads -in @(1,2,4) -and $candidate.endpointMillis -in @(250,350,450)) 'candidate enums'
Assert-Condition (($data.nativeExecuted -is [bool]) -and ($data.nativeLifecyclePassed -is [bool]) -and ($data.interrupted -is [bool])) 'native booleans are booleans'
$windows = @($data.windows)
$audio = 0.0
for ($index = 0; $index -lt $windows.Count; $index++) {
    $item = $windows[$index]
    Assert-Condition ($item.index -eq $index + 1) "window $($index + 1) is sequential"
    # A reconciled short run shortens its final window, so ten seconds is the upper bound for every window
    # and the exact requirement for all but the last. The sum check below carries the real constraint.
    Assert-Condition ($item.audioSeconds -gt 0 -and $item.audioSeconds -le 10) "window $($index + 1) audioSeconds in (0, 10]"
    if ($index -lt $windows.Count - 1) {
        Assert-Condition ($item.audioSeconds -eq 10) "window $($index + 1) audioSeconds == 10"
    }
    foreach ($field in @('queueDepth','speakerCalls','faults','droppedFrames')) {
        $value = $item.$field
        Assert-Condition ($value -ge 0 -and $value -le 1000000000 -and $value -eq [math]::Floor($value)) "window $($index + 1) $field integral"
    }
    $audio += $item.audioSeconds
}
$result.rows = $windows.Count
Assert-Condition ([math]::Abs($audio - $data.measuredSeconds) -lt 0.000001) 'window audio sum == measuredSeconds'
$trials = @($data.semanticTrials)
Assert-Condition ($trials.Count -le 30) 'at most 30 trials'
$ids = @{}
foreach ($trial in $trials) {
    Assert-Condition ($trial.id -cmatch '^P2Q(00[1-9]|0[12][0-9]|030)$') "trial id $($trial.id)"
    Assert-Condition (-not $ids.ContainsKey($trial.id)) 'trial ids unique'
    Assert-Condition ($trial.correct -is [bool]) 'trial correctness is boolean'
    $ids[$trial.id] = $true
}
# New evidence fields: must parse and must never be null-valued placeholders.
$resources = @($data.resources)
$result.resources = $resources.Count
foreach ($row in $resources) {
    Assert-Condition ($null -ne $row.pssKiB -and $row.pssKiB -ge 0) 'resource pssKiB present'
    Assert-Condition ($null -ne $row.rssKiB) 'resource rssKiB key present'
    Assert-Condition ($null -ne $row.batteryTemperatureTenthsC) 'resource temperature key present'
    Assert-Condition ($null -ne $row.temperatureSource -and $row.temperatureSource.Length -gt 0) 'resource temperature source named'
}
Assert-Condition ($null -ne $data.identity) 'identity present'
Assert-Condition ($null -ne $data.identity.model -and $data.identity.model.Length -gt 0) 'identity model present'
Assert-Condition ($null -ne $data.identity.sdkInt) 'identity sdkInt present'
Assert-Condition ($null -ne $data.identity.abi -and $data.identity.abi.Length -gt 0) 'identity abi present'
$result.identity = $true
$result.parsed = $true
$result | ConvertTo-Json -Depth 4
