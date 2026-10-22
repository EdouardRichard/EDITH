# Classify the F4 / M2 turns for EDITH 0.21 Phase 7 with the PRE-REGISTERED rule.
#
# Design: docs/superpowers/specs/2026-10-17-edith-0.21-phase-7-measurement-design.md sections 2.6 + 2.8
# Brief:  .superpowers/sdd/2026-10-17-edith-0.21-phase-7-measurement/task-7-brief.md
#
# The keyword lists and the label table below are the pre-registered classifier. They may NOT be
# extended or reinterpreted after data collection. The raw assistant text stays under .local/:
# this script reads it only to classify a turn, never echoes it, and never writes it to the output
# CSV. Only labels, reason codes, character counts, SHA-256 values and the media/foreground
# fields leave this script.
#
# THIS FILE MUST STAY UTF-8 *WITH BOM*: Windows PowerShell 5.1 reads a BOM-less .ps1 as CP936,
# which corrupts the CJK keyword literals. Assert-F4KeywordEncoding checks the codepoints the
# classifier actually uses, so a source-encoding mishap fails loudly instead of mislabelling.
#
# Input CSV (Task 8 emits it), required columns:
#   slot,form,valid,asrChars,asrSha256,asrHasMusic,asrHasYueban,
#   answerChars,answerSha256,mediaTitle,mediaState,foreground,memoryChars,route,turnId,attempt
# Answer files: <AnswerDir>/f4-assistant-<slot>.txt (UTF-8, BOM optional).
#
# Field contract used by this script:
#   - valid: 'true'|'1' => classify; 'false'|'0'|'' => INVALID_SLOT, answer file NOT read;
#     any other value is an error (never guessed).
#   - answerChars: decoded text length of f4-assistant-<slot>.txt (.NET string length;
#     UTF-16 code units). Compared when non-empty; mismatch is fatal.
#   - answerSha256: SHA-256 over the answer file's RAW BYTES, hex (case-insensitive in the CSV).
#     Compared when non-empty; mismatch is fatal.
#   - mediaTitle/mediaState/foreground: trimmed before classification and echoed in the output.
#   - outputChars/outputSha256 are computed by this script from the answer file.
#
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/p7-f4/Classify-F4.ps1 -SelfTest
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/p7-f4/Classify-F4.ps1 `
#       -InputCsv .local/p7-f4/raw-slots.csv -AnswerDir .local/p7-f4/answers -OutputCsv .local/p7-f4/f4-labels.csv
param(
    [string]$InputCsv,
    [string]$AnswerDir,
    [string]$OutputCsv,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# --- Pre-registered constants (design section 2.6) --------------------------------

# Output channel keywords, applied to the assistant's final text.
$MemoryKeywords = @('稻香', '周杰伦')
$CurrentKeywords = @('月半小夜曲', '李克勤')

# Action channel: current request is fulfilled only for this music app, in the playing state,
# with the pre-registered track title. The memory action channel reuses the memory keywords
# against mediaTitle (the CSV carries the track title; an artist match lands in mediaTitle too).
$MediaCurrentTitleKeywords = @('月半小夜曲')
$PlayerPackage = 'com.miui.player'
$PlayingState = 'PLAYING'

$RequiredColumns = @(
    'slot', 'form', 'valid', 'asrChars', 'asrSha256', 'asrHasMusic', 'asrHasYueban',
    'answerChars', 'answerSha256', 'mediaTitle', 'mediaState', 'foreground', 'memoryChars',
    'route', 'turnId', 'attempt'
)

$OutputColumns = @(
    'slot', 'form', 'valid', 'label', 'reason', 'outputChars', 'outputSha256',
    'mediaTitle', 'mediaState', 'foreground', 'm2Success'
)

# --- Helpers ----------------------------------------------------------------------

# Codepoint guard for the pre-registered CJK literals. Called before every run.
function Assert-F4KeywordEncoding {
    $expected = [ordered]@{
        '稻香'       = @(0x7A3B, 0x9999)
        '周杰伦'     = @(0x5468, 0x6770, 0x4F26)
        '月半小夜曲' = @(0x6708, 0x534A, 0x5C0F, 0x591C, 0x66F2)
        '李克勤'     = @(0x674E, 0x514B, 0x52E4)
    }
    foreach ($keyword in $expected.Keys) {
        $want = @($expected[$keyword])
        $actual = @($keyword.ToCharArray() | ForEach-Object { [int]$_ })
        if ($actual.Count -ne $want.Count) {
            throw ("KEYWORD_CORRUPTED chars={0} expected={1}; keep this script UTF-8 with BOM" -f `
                    $actual.Count, $want.Count)
        }
        for ($i = 0; $i -lt $want.Count; $i++) {
            if ($actual[$i] -ne $want[$i]) {
                throw ("KEYWORD_CORRUPTED index={0} got=U+{1:X4} expected=U+{2:X4}; keep this script UTF-8 with BOM" -f `
                        $i, $actual[$i], $want[$i])
            }
        }
    }
    # The guard must cover the strings the classifier really uses.
    foreach ($keyword in @($MemoryKeywords + $CurrentKeywords + $MediaCurrentTitleKeywords)) {
        if (-not $expected.Contains($keyword)) {
            throw ("KEYWORD_NOT_GUARDED '{0}'" -f $keyword)
        }
    }
}

# --- Small shared helpers ---------------------------------------------------------

function Get-TrimmedText {
    param($Value)
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

function Test-TextContains {
    param(
        [string]$Text,
        [string[]]$Needles
    )
    if ([string]::IsNullOrEmpty($Text)) { return $false }
    foreach ($needle in $Needles) {
        if ([string]::IsNullOrEmpty($needle)) { continue }
        if ($Text.IndexOf($needle, [System.StringComparison]::Ordinal) -ge 0) { return $true }
    }
    return $false
}

# --- Classifier (design section 2.6) ----------------------------------------------

# Output channels from the assistant's final text.
function Get-F4Channels {
    param([string]$Text)
    $body = Get-TrimmedText $Text
    return [pscustomobject]@{
        memoryOutput  = (Test-TextContains -Text $body -Needles $MemoryKeywords)
        currentOutput = (Test-TextContains -Text $body -Needles $CurrentKeywords)
    }
}

# Label + reason code from the text and the system action/media signals.
# Label table (design section 2.6, pre-registered; do not extend after data):
#   memoryResponded && currentFulfilled          -> MIXED
#   memoryResponded && !currentFulfilled         -> MEMORY
#   !memoryResponded && currentFulfilled         -> CURRENT (CURRENT_ACTION)
#   !memoryResponded && !currentFulfilled && currentOutput -> CURRENT (CURRENT_OUTPUT_NO_ACTION)
#   otherwise                                    -> NEITHER
function Get-F4Label {
    param(
        [string]$Text,
        [string]$MediaTitle,
        [string]$MediaState,
        [string]$Foreground
    )
    $channels = Get-F4Channels -Text $Text
    $title = Get-TrimmedText $MediaTitle
    $state = Get-TrimmedText $MediaState
    $app = Get-TrimmedText $Foreground

    # Action channel: mediaTitle carries the track; an artist match is in the same field.
    $memoryPlayed = Test-TextContains -Text $title -Needles $MemoryKeywords
    $memoryResponded = $channels.memoryOutput -or $memoryPlayed
    $currentFulfilled = Get-M2Success -MediaTitle $title -MediaState $state -Foreground $app

    if ($memoryResponded -and $currentFulfilled) {
        return [pscustomobject]@{ Label = 'MIXED'; Reason = 'MIXED' }
    }
    if ($memoryResponded) {
        if ($channels.memoryOutput -and $memoryPlayed) {
            $reason = 'MEMORY_BOTH'
        }
        elseif ($channels.memoryOutput) {
            $reason = 'MEMORY_OUTPUT'
        }
        else {
            $reason = 'MEMORY_ACTION'
        }
        return [pscustomobject]@{ Label = 'MEMORY'; Reason = $reason }
    }
    if ($currentFulfilled) {
        return [pscustomobject]@{ Label = 'CURRENT'; Reason = 'CURRENT_ACTION' }
    }
    if ($channels.currentOutput) {
        return [pscustomobject]@{ Label = 'CURRENT'; Reason = 'CURRENT_OUTPUT_NO_ACTION' }
    }
    return [pscustomobject]@{ Label = 'NEITHER'; Reason = 'NEITHER' }
}

# M2 turn success == currentFulfilled (design sections 2.6 and 2.8).
function Get-M2Success {
    param(
        [string]$MediaTitle,
        [string]$MediaState,
        [string]$Foreground
    )
    $title = Get-TrimmedText $MediaTitle
    $state = Get-TrimmedText $MediaState
    $app = Get-TrimmedText $Foreground
    $inPlayer = ($app -eq $PlayerPackage)
    $isPlaying = ($state -eq $PlayingState)
    $titleIsCurrent = Test-TextContains -Text $title -Needles $MediaCurrentTitleKeywords
    return ($inPlayer -and $isPlaying -and $titleIsCurrent)
}

# --- Self-test --------------------------------------------------------------------

function Invoke-F4SelfTest {
    $cases = @(
        [pscustomobject]@{
            N = 1; Text = '好的，正在为你播放周杰伦的稻香。'; MediaTitle = '稻香'
            MediaState = 'PLAYING'; Foreground = 'com.miui.player'
            ExpectedLabel = 'MEMORY'; AllowedReasons = @('MEMORY_OUTPUT', 'MEMORY_BOTH'); ExpectedM2 = $false
        },
        [pscustomobject]@{
            N = 2; Text = '正在播放李克勤的月半小夜曲。'; MediaTitle = '月半小夜曲'
            MediaState = 'PLAYING'; Foreground = 'com.miui.player'
            ExpectedLabel = 'CURRENT'; AllowedReasons = @('CURRENT_ACTION'); ExpectedM2 = $true
        },
        [pscustomobject]@{
            N = 3; Text = '已为你播放月半小夜曲，另外你之前提过周杰伦的稻香。'; MediaTitle = '月半小夜曲'
            MediaState = 'PLAYING'; Foreground = 'com.miui.player'
            ExpectedLabel = 'MIXED'; AllowedReasons = @('MIXED'); ExpectedM2 = $true
        },
        [pscustomobject]@{
            N = 4; Text = '抱歉，我没听清。'; MediaTitle = ''
            MediaState = ''; Foreground = 'com.miui.home'
            ExpectedLabel = 'NEITHER'; AllowedReasons = @('NEITHER'); ExpectedM2 = $false
        },
        [pscustomobject]@{
            N = 5; Text = '好的，正在播放月半小夜曲。'; MediaTitle = ''
            MediaState = ''; Foreground = 'com.miui.home'
            ExpectedLabel = 'CURRENT'; AllowedReasons = @('CURRENT_OUTPUT_NO_ACTION'); ExpectedM2 = $false
        },
        [pscustomobject]@{
            N = 6; Text = '周杰伦的稻香很好听。'; MediaTitle = '月半小夜曲'
            MediaState = 'PLAYING'; Foreground = 'com.miui.player'
            ExpectedLabel = 'MIXED'; AllowedReasons = @('MIXED'); ExpectedM2 = $true
        },
        [pscustomobject]@{
            N = 7; Text = '正在播放李克勤的月半小夜曲。'; MediaTitle = '稻香'
            MediaState = 'PLAYING'; Foreground = 'com.miui.player'
            ExpectedLabel = 'MEMORY'; AllowedReasons = @('MEMORY_ACTION'); ExpectedM2 = $false
        }
    )

    $failed = @()
    foreach ($case in $cases) {
        $actualLabel = '<error>'
        $actualReason = '<error>'
        $actualM2 = '<error>'
        $problems = @()
        try {
            $verdict = Get-F4Label -Text $case.Text -MediaTitle $case.MediaTitle `
                -MediaState $case.MediaState -Foreground $case.Foreground
            $actualLabel = [string]$verdict.Label
            $actualReason = [string]$verdict.Reason
            $actualM2 = Get-M2Success -MediaTitle $case.MediaTitle -MediaState $case.MediaState `
                -Foreground $case.Foreground
        }
        catch {
            $problems += ('error={0}' -f $_.Exception.Message)
        }
        if ($problems.Count -eq 0) {
            if ($actualLabel -ne $case.ExpectedLabel) {
                $problems += ('label expected={0} actual={1}' -f $case.ExpectedLabel, $actualLabel)
            }
            if (-not ($case.AllowedReasons -contains $actualReason)) {
                $problems += ('reason expected={0} actual={1}' -f ($case.AllowedReasons -join '|'), $actualReason)
            }
            if ($actualM2 -ne $case.ExpectedM2) {
                $problems += ('m2 expected={0} actual={1}' -f $case.ExpectedM2, $actualM2)
            }
        }
        if ($problems.Count -gt 0) {
            $failed += ('CASE {0} FAIL {1}' -f $case.N, ($problems -join '; '))
        }
        else {
            Write-Host ('CASE {0} PASS label={1} reason={2} m2={3}' -f `
                    $case.N, $actualLabel, $actualReason, ([string]$actualM2).ToLowerInvariant())
        }
    }

    if ($failed.Count -gt 0) {
        foreach ($line in $failed) { Write-Host ('SELFTEST MISMATCH {0}' -f $line) }
        Write-Host ('SELFTEST FAIL failures={0}/{1}' -f $failed.Count, $cases.Count)
        return $false
    }
    return $true
}

# --- CSV classification -----------------------------------------------------------

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash($Bytes)
        return ([System.BitConverter]::ToString($hash)).Replace('-', '')
    }
    finally {
        $sha.Dispose()
    }
}

# Header names are the simple identifiers from the Task 8 contract, so a comma split is enough.
function Get-CsvHeaderColumns {
    param([string]$Path)
    $line = Get-Content -LiteralPath $Path -TotalCount 1 -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($line)) { throw ("EMPTY_CSV {0}" -f $Path) }
    return @($line -split ',' | ForEach-Object { $_.Trim().Trim('"') })
}

# 'true'|'1' => valid, 'false'|'0'|'' => invalid. Anything else is an error, never a guess.
function ConvertTo-ValidFlag {
    param([string]$Value, [string]$Slot)
    $flag = (Get-TrimmedText $Value).ToLowerInvariant()
    if ($flag -eq 'true' -or $flag -eq '1') { return $true }
    if ($flag -eq 'false' -or $flag -eq '0' -or $flag -eq '') { return $false }
    throw ("INVALID_VALID_FLAG slot={0} value='{1}'" -f $Slot, $Value)
}

# Slots become answer-file names; keep them path-safe and free of CSV-hostile characters.
function Assert-SafeSlot {
    param([string]$Slot)
    if ($Slot -notmatch '^[A-Za-z0-9_-]+$') {
        throw ("UNSAFE_SLOT value='{0}'" -f $Slot)
    }
}

# Reads the assistant answer text for classification. The text stays in memory only.
function Get-AnswerRecord {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw ("MISSING_ANSWER_FILE {0}" -f $Path)
    }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0) { throw ("EMPTY_ANSWER_FILE {0}" -f $Path) }
    # File.ReadAllText with an explicit encoding still honours and strips a BOM.
    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($text)) { throw ("BLANK_ANSWER_FILE {0}" -f $Path) }
    return [pscustomobject]@{
        Text   = $text
        Chars  = $text.Length
        Sha256 = (Get-Sha256Hex -Bytes $bytes)
    }
}

function Invoke-F4Classification {
    param(
        [string]$InputCsvPath,
        [string]$AnswerDirectory,
        [string]$OutputCsvPath
    )

    if (-not (Test-Path -LiteralPath $InputCsvPath -PathType Leaf)) {
        throw ("FILE_NOT_FOUND -InputCsv {0}" -f $InputCsvPath)
    }
    if (-not (Test-Path -LiteralPath $AnswerDirectory -PathType Container)) {
        throw ("DIRECTORY_NOT_FOUND -AnswerDir {0}" -f $AnswerDirectory)
    }

    $headerColumns = Get-CsvHeaderColumns -Path $InputCsvPath
    $missingColumns = @($RequiredColumns | Where-Object { $headerColumns -notcontains $_ })
    if ($missingColumns.Count -gt 0) {
        throw ("MISSING_COLUMNS {0}" -f ($missingColumns -join ','))
    }

    $csvText = Get-Content -LiteralPath $InputCsvPath -Raw -Encoding UTF8
    $rows = @($csvText | ConvertFrom-Csv)

    $outputRows = @()
    $labelCounts = [ordered]@{ MEMORY = 0; CURRENT = 0; MIXED = 0; NEITHER = 0; INVALID = 0 }
    $invalid = 0
    $m2Success = 0

    foreach ($row in $rows) {
        $slot = Get-TrimmedText $row.slot
        if ($slot -eq '') { throw 'EMPTY_SLOT' }
        Assert-SafeSlot -Slot $slot
        $form = Get-TrimmedText $row.form
        $isValid = ConvertTo-ValidFlag -Value $row.valid -Slot $slot
        $mediaTitle = Get-TrimmedText $row.mediaTitle
        $mediaState = Get-TrimmedText $row.mediaState
        $foreground = Get-TrimmedText $row.foreground

        if (-not $isValid) {
            # Invalid slots are not classified and their answer file is deliberately not read.
            $invalid++
            $labelCounts['INVALID']++
            $outputRows += [pscustomobject][ordered]@{
                slot         = $slot
                form         = $form
                valid        = 'false'
                label        = 'INVALID'
                reason       = 'INVALID_SLOT'
                outputChars  = ''
                outputSha256 = ''
                mediaTitle   = $mediaTitle
                mediaState   = $mediaState
                foreground   = $foreground
                m2Success    = 'false'
            }
            continue
        }

        $answerPath = Join-Path -Path $AnswerDirectory -ChildPath ('f4-assistant-{0}.txt' -f $slot)
        $answer = Get-AnswerRecord -Path $answerPath

        # Integrity cross-check against Task 8's copy of the answer file metadata. Empty values
        # mean "not provided" and are skipped; non-empty mismatches are fatal, not silently fixed.
        $expectedCharsText = Get-TrimmedText $row.answerChars
        if ($expectedCharsText -ne '') {
            $expectedChars = 0
            if (-not [int]::TryParse($expectedCharsText, [ref]$expectedChars)) {
                throw ("INVALID_ANSWER_CHARS slot={0} value='{1}'" -f $slot, $expectedCharsText)
            }
            if ($expectedChars -ne $answer.Chars) {
                throw ("ANSWER_CHARS_MISMATCH slot={0} csv={1} actual={2}" -f $slot, $expectedChars, $answer.Chars)
            }
        }
        $expectedSha = Get-TrimmedText $row.answerSha256
        if ($expectedSha -ne '') {
            if (-not ($expectedSha -eq $answer.Sha256)) {
                throw ("ANSWER_SHA_MISMATCH slot={0} csv={1} actual={2}" -f $slot, $expectedSha, $answer.Sha256)
            }
        }

        $verdict = Get-F4Label -Text $answer.Text -MediaTitle $mediaTitle `
            -MediaState $mediaState -Foreground $foreground
        $m2 = Get-M2Success -MediaTitle $mediaTitle -MediaState $mediaState -Foreground $foreground
        $label = [string]$verdict.Label
        if ($m2) { $m2Success++ }
        $labelCounts[$label] = [int]$labelCounts[$label] + 1

        $outputRows += [pscustomobject][ordered]@{
            slot         = $slot
            form         = $form
            valid        = 'true'
            label        = $label
            reason       = [string]$verdict.Reason
            outputChars  = [string]$answer.Chars
            outputSha256 = $answer.Sha256
            mediaTitle   = $mediaTitle
            mediaState   = $mediaState
            foreground   = $foreground
            m2Success    = ([string]$m2).ToLowerInvariant()
        }
    }

    $fullOutputPath = $OutputCsvPath
    if (-not [System.IO.Path]::IsPathRooted($fullOutputPath)) {
        $fullOutputPath = Join-Path (Get-Location).Path $fullOutputPath
    }
    $outputDirectory = [System.IO.Path]::GetDirectoryName($fullOutputPath)
    if (-not [string]::IsNullOrEmpty($outputDirectory) -and -not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }

    if ($outputRows.Count -eq 0) {
        # Export-Csv writes nothing for an empty pipeline; write the header explicitly.
        Set-Content -LiteralPath $fullOutputPath -Value ($OutputColumns -join ',') -Encoding UTF8
    }
    else {
        $outputRows | Export-Csv -LiteralPath $fullOutputPath -NoTypeInformation -Encoding UTF8
    }

    $labelText = (@($labelCounts.Keys | ForEach-Object { '{0}:{1}' -f $_, $labelCounts[$_] })) -join ','
    return [pscustomobject]@{
        Rows        = $rows.Count
        Invalid     = $invalid
        M2Success   = $m2Success
        LabelCounts = $labelText
        OutputPath  = $fullOutputPath
    }
}

# --- Entry point ------------------------------------------------------------------

if ($SelfTest) {
    try {
        Assert-F4KeywordEncoding
    }
    catch {
        Write-Host ('SELFTEST ERROR {0}' -f $_.Exception.Message)
        exit 1
    }
    if (Invoke-F4SelfTest) {
        Write-Host 'SELFTEST PASS'
        exit 0
    }
    exit 1
}

try {
    if ([string]::IsNullOrWhiteSpace($InputCsv)) { throw 'PARAMETER_MISSING -InputCsv' }
    if ([string]::IsNullOrWhiteSpace($AnswerDir)) { throw 'PARAMETER_MISSING -AnswerDir' }
    if ([string]::IsNullOrWhiteSpace($OutputCsv)) { throw 'PARAMETER_MISSING -OutputCsv' }
    Assert-F4KeywordEncoding
    $summary = Invoke-F4Classification -InputCsvPath $InputCsv -AnswerDirectory $AnswerDir `
        -OutputCsvPath $OutputCsv
    Write-Host ('F4_CLASSIFY rows={0} invalid={1} labels={2} m2Success={3} out={4}' -f `
            $summary.Rows, $summary.Invalid, $summary.LabelCounts, $summary.M2Success, $summary.OutputPath)
}
catch {
    Write-Host ('F4_CLASSIFY ERROR {0}' -f $_.Exception.Message)
    exit 1
}
exit 0
