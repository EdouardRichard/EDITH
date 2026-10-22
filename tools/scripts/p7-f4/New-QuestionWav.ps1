# Generate the three fixed-question WAVs for EDITH 0.21 Phase 7 F4 / M2.
#
# Design: docs/superpowers/specs/2026-10-17-edith-0.21-phase-7-measurement-design.md section 2.2
# Brief:  .superpowers/sdd/2026-10-17-edith-0.21-phase-7-measurement/task-6-brief.md
#
# The question text is FIXED for the measurement and must not change between runs. The literal
# below is asserted against its Unicode codepoints, so a source-encoding mishap fails loudly
# instead of synthesizing different words. THIS FILE MUST STAY UTF-8 *WITH BOM*: Windows
# PowerShell 5.1 would otherwise decode the CJK literal as CP936 and speak mojibake.
#
# Output: <OutDir>/p7-question-music-rate{-2,0,2}.wav (default .local/p7-f4/wav, gitignored;
# binary WAVs are never committed - only this generator and its README are).
# Requested geometry: 16 kHz / mono / 16-bit PCM. If the selected voice refuses it, the script
# falls back to the voice default WAV format and prints a WARNING; the ACTUAL format is always
# read back from the WAV `fmt ` chunk and printed, because the injector resamples but evidence
# must record what was really produced.
param(
    [string]$OutDir = ".local\p7-f4\wav"
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$VoiceName = 'Microsoft Huihui Desktop'
# Fixed F4 question. Codepoints: 打 开 音 乐 ， 播 放 李 克 勤 的 月 半 小 夜 曲
$Text = '打开音乐，播放李克勤的月半小夜曲'
$ExpectedCodepoints = @(
    0x6253, 0x5F00, 0x97F3, 0x4E50, 0xFF0C, 0x64AD, 0x653E, 0x674E,
    0x514B, 0x52E4, 0x7684, 0x6708, 0x534A, 0x5C0F, 0x591C, 0x66F2
)
$Rates = @(-2, 0, 2)
$RequestedSampleRate = 16000
$RequestedChannels = 1
$RequestedBits = 16
$RequestedFormatText = '16000 Hz / mono / 16-bit PCM'

function Get-FormatTagName {
    param([int]$Tag)
    if ($Tag -eq 1) { return 'PCM' }
    if ($Tag -eq 3) { return 'IEEE_FLOAT' }
    if ($Tag -eq 6) { return 'A_LAW' }
    if ($Tag -eq 7) { return 'MU_LAW' }
    if ($Tag -eq 0xFFFE) { return 'WAVE_FORMAT_EXTENSIBLE' }
    return ("UNKNOWN(0x{0:X4})" -f $Tag)
}

function Get-ChannelName {
    param([int]$Channels)
    if ($Channels -eq 1) { return 'mono' }
    if ($Channels -eq 2) { return 'stereo' }
    return ("{0} channels" -f $Channels)
}

# Parse RIFF/WAVE and the `fmt ` + `data` chunks. Throws if the file is not a real WAV.
function Get-WavInfo {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 12) {
        throw ("not a WAV (too short): {0} ({1} bytes)" -f $Path, $bytes.Length)
    }
    $riff = [System.Text.Encoding]::ASCII.GetString($bytes, 0, 4)
    $wave = [System.Text.Encoding]::ASCII.GetString($bytes, 8, 4)
    if ($riff -ne 'RIFF') { throw ("WAV missing RIFF signature: {0} (found '{1}')" -f $Path, $riff) }
    if ($wave -ne 'WAVE') { throw ("WAV missing WAVE signature: {0} (found '{1}')" -f $Path, $wave) }

    $fmt = $null
    $dataBytes = -1L
    $offset = 12
    while (($offset + 8) -le $bytes.Length) {
        $chunkId = [System.Text.Encoding]::ASCII.GetString($bytes, $offset, 4)
        $chunkSize = [System.BitConverter]::ToUInt32($bytes, $offset + 4)
        $chunkData = $offset + 8
        if (($chunkId -eq 'fmt ') -and ($null -eq $fmt)) {
            if ($chunkSize -lt 16) { throw ("fmt chunk too small in {0}" -f $Path) }
            $fmt = [pscustomobject]@{
                FormatTag     = [System.BitConverter]::ToUInt16($bytes, $chunkData)
                Channels      = [System.BitConverter]::ToUInt16($bytes, $chunkData + 2)
                SampleRate    = [System.BitConverter]::ToUInt32($bytes, $chunkData + 4)
                ByteRate      = [System.BitConverter]::ToUInt32($bytes, $chunkData + 8)
                BlockAlign    = [System.BitConverter]::ToUInt16($bytes, $chunkData + 12)
                BitsPerSample = [System.BitConverter]::ToUInt16($bytes, $chunkData + 14)
            }
        }
        elseif ($chunkId -eq 'data') {
            $dataBytes = [long]$chunkSize
        }
        # RIFF chunks are word-aligned: an odd payload is followed by one pad byte.
        $offset = $chunkData + [int]$chunkSize + ([int]$chunkSize % 2)
    }
    if ($null -eq $fmt) { throw ("WAV has no fmt chunk: {0}" -f $Path) }
    if ($dataBytes -lt 0) { throw ("WAV has no data chunk: {0}" -f $Path) }

    [pscustomobject]@{
        Riff          = $riff
        Wave          = $wave
        FormatTag     = $fmt.FormatTag
        FormatName    = (Get-FormatTagName $fmt.FormatTag)
        Channels      = $fmt.Channels
        SampleRate    = $fmt.SampleRate
        ByteRate      = $fmt.ByteRate
        BlockAlign    = $fmt.BlockAlign
        BitsPerSample = $fmt.BitsPerSample
        DataBytes     = $dataBytes
        FileBytes     = $bytes.Length
    }
}

function Invoke-Synthesis {
    param(
        [string]$Path,
        [int]$Rate,
        [bool]$UseExplicitFormat
    )
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.SelectVoice($VoiceName)
        $synth.Rate = $Rate
        $synth.Volume = 100
        if ($UseExplicitFormat) {
            $synth.SetOutputToWaveFile($Path, $explicitFormat)
        }
        else {
            $synth.SetOutputToWaveFile($Path)
        }
        $synth.Speak($Text)
        $synth.SetOutputToNull()
    }
    finally {
        $synth.Dispose()
    }
}

# --- 1. Fixed text guard -------------------------------------------------------

$actualCodepoints = @($Text.ToCharArray() | ForEach-Object { [int]$_ })
if ($actualCodepoints.Count -ne $ExpectedCodepoints.Count) {
    throw ("FIXED TEXT CORRUPTED: got {0} chars, expected {1}. Keep this script UTF-8 with BOM." -f $actualCodepoints.Count, $ExpectedCodepoints.Count)
}
for ($i = 0; $i -lt $ExpectedCodepoints.Count; $i++) {
    if ($actualCodepoints[$i] -ne $ExpectedCodepoints[$i]) {
        throw ("FIXED TEXT CORRUPTED at index {0}: U+{1:X4}, expected U+{2:X4}. Keep this script UTF-8 with BOM." -f $i, $actualCodepoints[$i], $ExpectedCodepoints[$i])
    }
}

$textHasher = [System.Security.Cryptography.SHA256]::Create()
try {
    $textHash = [System.BitConverter]::ToString($textHasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))).Replace('-', '')
}
finally {
    $textHasher.Dispose()
}

# --- 2. Voice selection --------------------------------------------------------

$probe = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $installed = @($probe.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo })
}
finally {
    $probe.Dispose()
}

$voiceList = '(none)'
if ($installed.Count -gt 0) { $voiceList = ($installed | ForEach-Object { $_.Name }) -join '; ' }
$voice = $installed | Where-Object { $_.Name -eq $VoiceName } | Select-Object -First 1
if ($null -eq $voice) {
    throw ("VOICE_NOT_FOUND: '{0}' is not installed. Installed voices: {1}" -f $VoiceName, $voiceList)
}

Write-Host ("VOICE name='{0}' culture='{1}' gender='{2}'" -f $voice.Name, $voice.Culture.Name, $voice.Gender)
Write-Host ("TEXT chars={0} utf8Bytes={1} sha256={2}" -f $Text.Length, ([System.Text.Encoding]::UTF8.GetByteCount($Text)), $textHash)
Write-Host ("REQUESTED_FORMAT {0}" -f $RequestedFormatText)

# --- 3. Output directory -------------------------------------------------------

$outDirAbs = $OutDir
if (-not [System.IO.Path]::IsPathRooted($outDirAbs)) {
    $outDirAbs = Join-Path -Path (Get-Location).Path -ChildPath $outDirAbs
}
if (-not (Test-Path -LiteralPath $outDirAbs)) {
    New-Item -ItemType Directory -Path $outDirAbs -Force | Out-Null
}
Write-Host ("OUTDIR {0}" -f $outDirAbs)

$explicitFormat = New-Object -TypeName System.Speech.AudioFormat.SpeechAudioFormatInfo -ArgumentList @(
    $RequestedSampleRate,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono
)

# --- 4. Synthesize + verify ----------------------------------------------------

$results = @()
$anyFallback = $false

foreach ($rate in $Rates) {
    $file = "p7-question-music-rate{0}.wav" -f $rate
    $path = Join-Path -Path $outDirAbs -ChildPath $file
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }

    $fallback = $false
    try {
        Invoke-Synthesis -Path $path -Rate $rate -UseExplicitFormat $true
    }
    catch {
        $reason = $_.Exception.Message
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        Write-Host ("WARNING rate={0}: voice '{1}' refused {2} ({3})" -f $rate, $VoiceName, $RequestedFormatText, $reason)
        Write-Host ("WARNING rate={0}: falling back to the voice default WAV format; the injector resamples. Actual format is reported below." -f $rate)
        $fallback = $true
        Invoke-Synthesis -Path $path -Rate $rate -UseExplicitFormat $false
    }

    if (-not (Test-Path -LiteralPath $path)) { throw ("MISSING WAV after synthesis: {0}" -f $path) }
    $size = (Get-Item -LiteralPath $path).Length
    if ($size -le 44) { throw ("EMPTY WAV (header only, {0} bytes): {1}" -f $size, $path) }

    $info = Get-WavInfo -Path $path
    if ($info.DataBytes -le 0) { throw ("EMPTY WAV data chunk: {0}" -f $path) }

    $sha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    $duration = [math]::Round($info.DataBytes / [double]$info.ByteRate, 2)
    $formatMatches = (($info.FormatTag -eq 1) -or ($info.FormatTag -eq 0xFFFE)) -and
        ($info.SampleRate -eq $RequestedSampleRate) -and
        ($info.Channels -eq $RequestedChannels) -and
        ($info.BitsPerSample -eq $RequestedBits)
    $isFallback = $fallback -or (-not $formatMatches)
    if ($isFallback) { $anyFallback = $true }

    Write-Host ""
    Write-Host ("FILE {0}" -f $file)
    Write-Host ("  bytes    = {0}" -f $size)
    Write-Host ("  sha256   = {0}" -f $sha)
    Write-Host ("  header   = {0}/{1} | {2} | {3} Hz | {4} | {5}-bit | blockAlign={6} | byteRate={7} | dataBytes={8}" -f `
            $info.Riff, $info.Wave, $info.FormatName, $info.SampleRate, (Get-ChannelName $info.Channels), `
            $info.BitsPerSample, $info.BlockAlign, $info.ByteRate, $info.DataBytes)
    Write-Host ("  duration = {0} s (rate {1})" -f $duration, $rate)
    if ($isFallback) {
        Write-Host ("  format   = FALLBACK: requested {0}, produced {1} Hz / {2} / {3}-bit (injector resamples)" -f `
                $RequestedFormatText, $info.SampleRate, (Get-ChannelName $info.Channels), $info.BitsPerSample)
    }
    else {
        Write-Host ("  format   = as requested ({0})" -f $RequestedFormatText)
    }

    $results += [pscustomobject]@{
        File       = $file
        Path       = $path
        Bytes      = $size
        Sha256     = $sha
        SampleRate = $info.SampleRate
        Channels   = $info.Channels
        Bits       = $info.BitsPerSample
        FormatTag  = $info.FormatName
        DurationS  = $duration
        Fallback   = $isFallback
    }
}

if ($results.Count -ne $Rates.Count) {
    throw ("expected {0} WAVs, produced {1}" -f $Rates.Count, $results.Count)
}

Write-Host ""
Write-Host ("WAV_DONE files={0} outDir={1} voice='{2}' requestedFormat='{3}' fallback={4}" -f `
        $results.Count, $outDirAbs, $VoiceName, $RequestedFormatText, $anyFallback)
