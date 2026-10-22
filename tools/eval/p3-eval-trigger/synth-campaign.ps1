# Synthesize one WAV per line of a UTF-8 manifest, for the P3.4 trigger campaign.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File synth-campaign.ps1 `
#       -ManifestPath <utf8 tsv: id<TAB>text> -Dir <output dir>
#
# ASCII-ONLY SOURCE, on purpose: this host's Windows PowerShell reads BOM-less .ps1 files as
# CP936, so a CJK literal in this file would be corrupted before it ever ran, and the corpus
# text would be spoken wrong. The Chinese text lives in the manifest and is read back with an
# EXPLICIT UTF-8 decoder. The manifest's second column is the LAST tab-separated field, so a
# text containing a tab is still carried whole.
#
# Geometry 16 kHz / 16-bit / mono - the geometry the P3.1+ capture chain runs at.
# Already-present WAVs are NOT re-synthesized (a resume must not re-spend the time, and a
# re-synth could differ from the clip the earlier rows were measured with).
param(
    [Parameter(Mandatory = $true)][string]$ManifestPath,
    [Parameter(Mandatory = $true)][string]$Dir,
    [string]$Voice = 'Microsoft Huihui Desktop'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

if (-not (Test-Path $ManifestPath)) { throw "manifest not found: $ManifestPath" }
if (-not (Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir -Force | Out-Null }

$manifest = [System.IO.File]::ReadAllText($ManifestPath, [System.Text.UTF8Encoding]::new($false))
$lines = @($manifest -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 -and -not $_.Trim().StartsWith('#') })
Write-Host ("manifest non-empty lines = " + $lines.Count)

$fmt = New-Object -TypeName System.Speech.AudioFormat.SpeechAudioFormatInfo -ArgumentList @(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

$made = 0
$kept = 0
foreach ($line in $lines) {
    $tab = $line.IndexOf("`t")
    if ($tab -lt 1) { throw "manifest line has no tab separator: [$line]" }
    $id = $line.Substring(0, $tab).Trim()
    $text = $line.Substring($tab + 1)
    if ($text.Trim().Length -eq 0) { throw "empty text for id $id" }

    $path = Join-Path $Dir ($id + '.wav')
    if (Test-Path $path) {
        $kept++
        Write-Host ("keep " + $id + ".wav bytes=" + (Get-Item $path).Length)
        continue
    }

    $synth = New-Object -TypeName System.Speech.Synthesis.SpeechSynthesizer
    try {
        $synth.SelectVoice($Voice)
        $synth.Rate = 0
        $synth.Volume = 100
        $synth.SetOutputToWaveFile($path, $fmt)
        $synth.Speak($text)
        $synth.SetOutputToNull()
    } finally {
        $synth.Dispose()
    }

    if (-not (Test-Path $path)) { throw "synthesis produced no file for $id" }
    $bytes = (Get-Item $path).Length
    if ($bytes -le 44) { throw "synthesis produced an empty WAV (header only) for $id" }
    # Codepoints only: never the text itself, so this log stays ASCII and carries no corpus content.
    $cps = ($text.ToCharArray() | ForEach-Object { 'U+{0:X4}' -f [int]$_ }) -join ' '
    Write-Host ("made " + $id + ".wav bytes=" + $bytes + " chineseChars=" + $text.Length + " codepoints=" + $cps)
    $made++
}

Write-Host ("SYNTH_DONE made=" + $made + " kept=" + $kept)
