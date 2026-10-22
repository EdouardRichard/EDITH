param([string]$OutputDirectory = "$PSScriptRoot/../../../.local/p2-engine-report-tests")
$ErrorActionPreference = 'Stop'
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$reporter = Join-Path $PSScriptRoot 'report-engine.ps1'
function New-ValidMetrics {
    return @{
        schemaVersion = 1; corpusId = 'p2-fixed-zh-30-v1'; warmupSeconds = 60; measuredSeconds = 600
        candidate = @{
            deviceId='synthetic-test-device'; runtimeSha256='633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96'
            sampleRateHz=16000; frameMillis=20; threads=2; endpointMillis=350; provider='cpu'
            modelHashes=@{
                encoder='8FA764187A261844F859D7143EBAA563AF5D10ADFECE4C18A8F414C88CBA2A9B'
                decoder='2E3B5EC371F8899EE6ACD829FD753BA45772DF57A91BDF37CDE3136354E7DB7D'
                joiner='1ED689C5ED19DBAA725D9D191BB4822B5F4855A39E1FFD28CBC1F340D25B2EE0'
                tokens='A8E0E4EC53810E433789B54A5C0134A7EAA2FFCA595A6334D54C00DA858841D3'
                vad='6B99CBFD39246B6706F98EC13C7C50C6B299181F2474FA05CBC8046ACC274396'
                speaker='1A331345F04805BADBB495C775A6DDFFCDD1A732567D5EC8B3D5749E3C7A5E4B'
            }
        }
        nativeLifecyclePassed = $true; nativeExecuted = $true; interrupted = $false
        windows = @(1..60 | ForEach-Object { @{ index=$_; audioSeconds=10; rtf=0.7; queueDepth=0; speakerCalls=1; faults=0; droppedFrames=0 } })
        semanticTrials = @(1..30 | ForEach-Object { @{ id=('P2Q{0:D3}' -f $_); correct=($_ -le 27) } })
    }
}
$script:passed = 0
$script:failed = 0
function Check-Report([string]$Name, [hashtable]$Metrics, [bool]$Selected, [double]$ExpectedP95 = -1) {
    try {
        $path = Join-Path $OutputDirectory "$Name.json"
        $Metrics | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8
        $result = (& $reporter -MetricsPath $path) | ConvertFrom-Json
        if ($result.selected -ne $Selected) { throw 'Wrong selection verdict' }
        if ($ExpectedP95 -ge 0 -and [math]::Abs($result.rtfP95 - $ExpectedP95) -gt 0.000001) { throw 'Wrong nearest-rank P95' }
        $script:passed++
        Write-Output "PASS $Name"
    } catch { $script:failed++; Write-Output "FAIL $Name : $($_.Exception.Message)" }
}
Check-Report 'thresholds' (New-ValidMetrics) $true 0.7
$data = New-ValidMetrics; $data.windows = @(1..60 | ForEach-Object { @{ index=$_; audioSeconds=10; rtf=$(if ($_ -le 57) { 0.2 } else { 0.8 }); queueDepth=0; speakerCalls=1; faults=0; droppedFrames=0 } })
Check-Report 'nearest-rank' $data $true 0.2
$data = New-ValidMetrics; $data.measuredSeconds = 590; $data.windows = $data.windows[0..58]
Check-Report 'short-run' $data $false
$data = New-ValidMetrics; $data.semanticTrials = $data.semanticTrials[0..28]
Check-Report 'missing-trial' $data $false
$data = New-ValidMetrics; $data.semanticTrials[26].correct = $false
Check-Report 'semantic-failure' $data $false
$data = New-ValidMetrics; $data.windows[59].queueDepth = 10
Check-Report 'growing-backlog' $data $false
$data = New-ValidMetrics; $data.windows[20].droppedFrames = 1
Check-Report 'dropped-work' $data $false
$data = New-ValidMetrics; $data.windows[20].rtf = -0.1
Check-Report 'invalid-rtf' $data $false
$data = New-ValidMetrics; $data.windows | ForEach-Object { $_.speakerCalls = 0 }
Check-Report 'unmeasured-speaker' $data $false
$data = New-ValidMetrics; $data.candidate.modelHashes.vad = ('0' * 64)
Check-Report 'wrong-model-identity' $data $false
Write-Output "RESULT passed=$script:passed failed=$script:failed"
if ($script:failed) { exit 1 }
