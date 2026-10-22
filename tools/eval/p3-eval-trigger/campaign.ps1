# =====================================================================================
# P3.4 annotated-corpus trigger campaign.
#
# Plays every corpus row through the phone's REAL proactive path (acoustic gate -> cheap
# filter -> one model request -> short hint or silence) and records, per row, whether the
# prompt fired. The scoring is NOT done here: `tools/p3-eval-trigger` (the CLI) owns the
# metric, and this script prints the exact command to run it.
#
# ASCII-ONLY SOURCE, on purpose (the P3.1 lesson, kept). This host's Windows PowerShell reads
# BOM-less .ps1 files as CP936, so a CJK literal anywhere in here would be a corruption
# hazard. The corpus text is never embedded: it is read from the corpus markdown with an
# explicit UTF-8 decoder and handed to the synthesizer through a UTF-8 manifest, never
# through a command line.
#
# Nothing in here is destructive: no rm, no package state changes, no writes outside the
# observations/evidence files, the audio directory and the device's own /data/local/tmp.
# =====================================================================================
param(
    [string]$CorpusPath = 'docs/project/evidence/2026-10-11-p3-4-eval-corpus.md',
    [string]$ObservationsPath = '.superpowers/sdd/2026-10-11-p3-4-memory-and-evaluation/trigger-observations.txt',
    [string]$AudioDir = '.local/p3-4/audio/corpus',
    [string]$DeviceDir = '/data/local/tmp/edith-audio',
    [string]$Scenario = '',
    [string]$Label = '',
    [int]$WaitSeconds = 12,
# Seconds to wait **before each row** (on top of the per-row window). This is how a batch can be measured at the product's
# own cooldown without touching the device's settings: with `-GapSeconds 60` no two utterances are close enough for the
# cooldown to suppress one, so a `COOLDOWN` skip can no longer be mistaken for the length gate refusing.
[int]$GapSeconds = 0,
    [int]$Limit = 0,
    [switch]$Synthesize,
    [switch]$DryRun,
    [switch]$Force,
    # Test-only: load the parser and stop. Never pass this to a real campaign.
    [switch]$EdithLibraryOnly
)

$ErrorActionPreference = 'Stop'

# -------------------------------------------------------------------------------------
# Library guard: a caller that passed `-EdithLibraryOnly` (a switch declared in the param
# block above and otherwise unused) gets the parser only, so CampaignParser.Tests.ps1 never
# touches adb. The flag is read from anywhere in the caller's command text rather than from a
# variable, because a variable set in the dot-sourcing caller's scope is NOT visible from
# here under `powershell -File` (measured on this host: Get-Variable -Scope 1 found nothing,
# and the guard silently failed open, running the whole live campaign from the test).
# Same split as the Kotlin scorer: pure parseLabels vs the process around it.
# -------------------------------------------------------------------------------------
$Script:EdithLibraryOnly = $false
$edithCaller = ''
try { if ($null -ne $MyInvocation) { $edithCaller = [string]$MyInvocation.Line } } catch { $edithCaller = '' }
if ($edithCaller -match 'EdithLibraryOnly') { $Script:EdithLibraryOnly = $true }
if ($PSBoundParameters.ContainsKey('EdithLibraryOnly') -and $EdithLibraryOnly) { $Script:EdithLibraryOnly = $true }

$CorpusScenarios = @('knowledge', 'privacy', 'dilemma', 'chat')
$CorpusLabels = @('positive', 'negative')
# `id` must be exactly the corpus shape: 1-3 capital letters, P/N, two digits.
$CorpusIdPattern = '^[A-Z]{1,3}-[PN]-[0-9]{2}$'
$InjectAction = 'edith.DEBUG_INJECT'
$InjectComponent = 'edith.phone/.DebugAudioInjector'
$InjectEndMarker = 'P3_INJECT state=end'
# The SUCCESS marker. The product logs `P3_PROACTIVE request ctx=5 chars=7` - the `=` belongs to `ctx`, NOT to
# `request`. This constant used to read 'P3_PROACTIVE request=', which matched nothing on any device: every real
# trigger was scored `no_decision_line_in_window`, which is the structural cause of the recorded recall 0 in P3.4
# (F19's `DEDUP` rows were the clue: successful hints really were happening and really were not being recorded).
# Matched WITHOUT the `=` so it holds for the real shape and would still hold if a future line adds one.
$TriggerMarker = 'P3_PROACTIVE request'
$CooldownMarker = 'P3_PROACTIVE skip reason=COOLDOWN'
# The criterion lines the per-row evidence keeps. `P3_ASR` (W4 diagnosis), `P3_TRANSCRIPT` (did a final reach the
# plugin?) and `P3_PROACTIVE request` (THE TRIGGER) are all needed: with only `P3_PROACTIVE skip`/`P3_ACOUSTIC`
# captured, a row that produced a final and NO recorded decision line is indistinguishable from a row whose trigger was
# never captured at all - which is exactly what the missing trigger marker produced.
#
# The tail of the chain (`P3_HTTP`, `P3_PROACTIVE hint`, `P3_HINT show`/`ack`, `P3_PROACTIVE silence`) is here for the
# same reason one step later: the verdict only needs the `request` line, but the ARTIFACT has to be able to show
# whether that request became a displayed hint (`request` -> `P3_HTTP 200` -> `hint` -> `ack Displayed`) instead of a
# `silence reason=NO_CALL`. Without them the causal chain P3.5-C cites can only be quoted from ad-hoc logcat, and a
# request that produced nothing looks exactly like one that showed a hint.
$NoiseMarkers = @(
    'P3_PROACTIVE skip', 'P3_PROACTIVE request', 'P3_PROACTIVE admit', 'P3_PROACTIVE silence', 'P3_PROACTIVE hint',
    'P3_ACOUSTIC', 'P3_SCRIPT', 'P3_ASR', 'P3_TRANSCRIPT', 'P3_HTTP', 'P3_HINT',
    # The instrument's own `state=start|refused|end`. `refused reason=...` is the ONLY line that distinguishes "the
    # injection was rejected" (e.g. NO_EPOCH after the app restarted with no armed session) from "the app decided
    # nothing": on 2026-10-12 04:35 a block of rows was written as `no_inject_end_in_window`, and the reason was
    # visible only in the live logcat - not in the artifact the scorer reads.
    'P3_INJECT')
$SynthScriptName = 'synth-campaign.ps1'

# =====================================================================================
# Pure parsing
# =====================================================================================

# Corpus markdown -> the rows this campaign may run.
#
# Only 5-column rows count, and only when cell 2 is one of the four scenarios and cell 3 is
# positive/negative - the same rule the scorer's parseLabels uses - AND the id matches the
# corpus shape. The extra id rule makes this row set a strict SUBSET of what the scorer
# accepts: a row this script runs is always scored, and a stray table row can never be
# played. (A row the scorer accepts but this rejects would be scored "not triggered" without
# being played, which is why nothing here may loosen the id rule silently - the dry run
# prints both counts and their difference.)
function ConvertTo-EdithCorpusRow {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Markdown)

    $rows = New-Object System.Collections.ArrayList
    foreach ($line in ($Markdown -split "`r?`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed.StartsWith('|')) { continue }
        $cells = @($trimmed.Trim('|') -split '\|' | ForEach-Object { $_.Trim() })
        if ($cells.Count -lt 5) { continue }
        $id = $cells[0]
        if ($id -notmatch $CorpusIdPattern) { continue }
        if ($CorpusScenarios -notcontains $cells[1]) { continue }
        if ($CorpusLabels -notcontains $cells[2]) { continue }
        [void]$rows.Add([pscustomobject]@{
                Id       = $id
                Scenario = $cells[1]
                Label    = $cells[2]
                Text     = $cells[3]
            })
    }
    return $rows
}

# The rows the SCORER will see, so the dry run can prove the campaign's set is a subset.
function Get-EdithScorerRowCount {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Markdown)
    $n = 0
    foreach ($line in ($Markdown -split "`r?`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed.StartsWith('|')) { continue }
        $cells = @($trimmed.Trim('|') -split '\|' | ForEach-Object { $_.Trim() })
        if ($cells.Count -lt 5) { continue }
        if ($CorpusScenarios -notcontains $cells[1]) { continue }
        if ($CorpusLabels -notcontains $cells[2]) { continue }
        $n++
    }
    return $n
}

# `ID=verdict` for every settled row, so a report can count verdicts without re-reading the
# file per row - and so the resume logic can tell a real verdict from a `harness_error` line,
# which must stay re-runnable. Comments and blank lines are ignored; a later line for the same
# id wins (the file is append-only, so the last line is the current state).
function Get-EdithObservationVerdict {
    param([string[]]$Lines)
    $map = @{}
    foreach ($line in $Lines) {
        if ($null -eq $line) { continue }
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) { continue }
        if ($trimmed.StartsWith('#')) { continue }
        if ($trimmed -notmatch '^([^=\s]+)\s*=\s*(\S*)\s*$') { continue }
        $map[$Matches[1]] = $Matches[2]
    }
    return $map
}

# `-Scenario knowledge,chat` -> @('knowledge','chat'); empty means "no filter".
function ConvertTo-EdithFilter {
    param([string]$Raw)
    $out = New-Object System.Collections.ArrayList
    foreach ($part in @($Raw -split ',')) {
        $p = $part.Trim()
        if ($p.Length -gt 0) { [void]$out.Add($p.ToLowerInvariant()) }
    }
    return $out
}

# logcat time prefix -> timestamp for the evidence file. Uses the DEVICE's own time, so a
# skewed host clock cannot make the evidence unreadable.
function Get-EdithLogTime {
    param([string]$Line)
    if ($Line -match '^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') { return $Matches[1] }
    return 'no-timestamp'
}

# Every line in $Tail that is evidence for one row's verdict: the trigger, or - when there
# is no trigger - the reason lines that explain it.
function Select-EdithEvidenceLine {
    param([string[]]$Tail)
    $hits = New-Object System.Collections.ArrayList
    foreach ($line in $Tail) {
        if ($line -like "*$TriggerMarker*") { [void]$hits.Add($line); continue }
        foreach ($marker in $NoiseMarkers) {
            if ($line -like "*$marker*") { [void]$hits.Add($line); break }
        }
    }
    return $hits
}

# =====================================================================================
# Pure verdict rule (W2 / F19)
# =====================================================================================

# One row's outcome from its own window, as a pure function so the rule is testable without adb.
#
# The rule this replaces wrote `none` for two INSTRUMENT outcomes and thereby folded them into "the
# decider said no":
#
#   * a window with **no decision line at all** (`none` + reason `no decision line in window`),
#   * a window whose only decision was a **COOLDOWN suppression** (`none` + a COOLDOWN skip line),
#
# and those are exactly the rows P3.4's recall rested on. A `COOLDOWN` suppression is not the decider
# answering - `admit()` applies the length check BEFORE the cooldown check, so a cooldown-suppressed
# row had already passed every content judgement and was silenced by the *instrument's own* spacing.
# Both are `harness_error`: unresolved, therefore re-runnable and never scored as a model silence.
#
# `none` now means one thing only: the decider spoke and the reason was not the instrument.
function Get-EdithRowVerdict {
    param([string[]]$Tail)
    $hits = @($Tail | Where-Object { $null -ne $_ })
    $injectEnd = @($hits | Where-Object { $_ -like "*$InjectEndMarker*" }).Count -gt 0
    $decision = @($hits | Where-Object { $_ -match 'P3_PROACTIVE skip reason=' }).Count -gt 0
    $cooldown = @($hits | Where-Object { $_ -like "*$CooldownMarker*" }).Count -gt 0
    $trigger = @($hits | Where-Object { $_ -like "*$TriggerMarker*" }).Count -gt 0
    if (-not $injectEnd) {
        return [pscustomobject]@{ Verdict = 'harness_error'; Reason = 'no_inject_end_in_window'
            DecisionSeen = $decision; CooldownSuppressed = $cooldown; InjectEndSeen = $false }
    }
    if ($trigger) {
        return [pscustomobject]@{ Verdict = 'triggered'; Reason = 'P3_PROACTIVE request'
            DecisionSeen = $true; CooldownSuppressed = $cooldown; InjectEndSeen = $true }
    }
    if ($cooldown) {
        return [pscustomobject]@{ Verdict = 'harness_error'; Reason = 'instrument_cooldown_suppressed'
            DecisionSeen = $decision; CooldownSuppressed = $true; InjectEndSeen = $true }
    }
    if ($decision) {
        return [pscustomobject]@{ Verdict = 'none'; Reason = 'P3_PROACTIVE skip reason'
            DecisionSeen = $true; CooldownSuppressed = $false; InjectEndSeen = $true }
    }
    return [pscustomobject]@{ Verdict = 'harness_error'; Reason = 'no_decision_line_in_window'
        DecisionSeen = $false; CooldownSuppressed = $false; InjectEndSeen = $true }
}

# The batch report's own sentence for the instrument-suppressed count, as a pure string so the wording
# cannot drift away from the rule above (and so a test can pin it).
function Get-EdithCooldownReportLine {
    param([int]$Count)
    return ("cooldown-suppressed (unresolved, re-run): " + $Count)
}

# The batch report's count block, as pure strings.
#
# Pure rather than inline `Write-Host`s (review I3) because the **nesting** is the thing that was wrong: the report used
# to print "none : 10" followed by an indented "of which WITHOUT a decision line ... : 1", while that row was in fact one
# of the `harness_error`s. A reader could not tell which parent the second number belonged to, and the pilot artifact
# proved it was the wrong one. Keeping the shape in one place lets a test assert the nesting instead of trusting it.
function Get-EdithBatchReportLines {
    param([int]$Triggered, [int]$None, [int]$HarnessErrors, [int]$NoDecision, [int]$Cooldown)
    return @(
        ("rows run        : " + ($Triggered + $None + $HarnessErrors)),
        ("triggered       : " + $Triggered),
        ("none            : " + $None + "   (the decider answered: SHORT/VAGUE/DEDUP/NOISY/BUSY/DISARMED)"),
        (Get-EdithCooldownReportLine -Count $Cooldown),
        ("  of the harness_errors, WITHOUT a decision line in the window: " + $NoDecision),
        ("harness_error   : " + $HarnessErrors + "   (unresolved: never scored as a model silence)")
    )
}

# A batch the app is not processing at all: the first few windows carry no criterion line of any kind.
#
# Why this, and not a pre-flight `P3_AUDIO state=armed` test (review I2 asked for that): measured on the K40, the main
# ring buffer is 2 MiB / ~60k lines, which is 1.5-2 hours of this campaign - `adb logcat -d -t 60000` mid-batch returned
# 60976 lines (the whole buffer, "beginning of main") and ZERO `P3_AUDIO state=` lines, because the session's arm line
# had long rotated out. A guard that refuses unless the newest arm line says armed would therefore refuse every
# legitimate mid-session re-run, and one that passes when the line is absent cannot catch the case it exists for.
# What a running session can always be asked is whether it reacts to injected audio at all - and that is the failure
# that actually cost a batch (W4c: injections into a stopped session wrote 10 rows with 849 window lines and zero
# feature lines, an artifact that reads like a product finding). Three consecutive silent windows cannot happen while
# the app is processing audio: every real injection in the artifacts produced at least `P3_ACOUSTIC label=Speech`.
function Test-EdithBatchIsDead {
    param([string[]]$RowWindows, [int]$MinimumRows = 3)
    $seen = @($RowWindows | Where-Object { $null -ne $_ })
    if ($seen.Count -lt $MinimumRows) { return $false }
    for ($i = 0; $i -lt $MinimumRows; $i++) {
        $lines = @($seen[$i] -split "`n")
        if (@($lines | Where-Object { $_ -match 'P3_ACOUSTIC|P3_ASR|P3_TRANSCRIPT|P3_PROACTIVE' }).Count -gt 0) { return $false }
    }
    return $true
}

# How long to keep collecting after the poll has seen THIS row's decision, before the final capture.
#
# Only a TRIGGER needs it. `P3_PROACTIVE request` is logged when the plugin decides to call the model; the call, the
# hint and the display ack follow ~0.2-2 s later (measured: request -> P3_HTTP 200 at +0.16 s -> hint at +1.6 s ->
# `P3_HINT ack status=Displayed` at +1.67 s). The poll breaks as soon as any decision line appears and the capture
# used to be taken immediately afterwards, so the artifact could hold `request` and NOTHING of the chain it was
# extended to show (measured on the first chain-audit row: request=1, P3_HTTP=0, P3_HINT=0). A refusing row
# (`skip reason=...`) is complete the moment it is logged, so it must not pay the wait.
function Get-EdithChainSettleSeconds {
    param([string[]]$PeekLines, [int]$SettleSeconds = 5)
    if (@($PeekLines | Where-Object { $_ -match 'P3_PROACTIVE request' }).Count -gt 0) { return $SettleSeconds }
    return 0
}

# =====================================================================================
# Side-effecting helpers
# =====================================================================================

function Write-EdithLine {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text
    )
    # UTF-8 without BOM, flushed immediately: a crash or a pulled cable must not lose a row
    # that was already measured.
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $writer = New-Object System.IO.StreamWriter($Path, $true, $encoding)
    try {
        $writer.WriteLine($Text)
        $writer.Flush()
        $writer.BaseStream.Flush()
    } finally {
        $writer.Dispose()
    }
}

function Get-EdithLogcatTail {
    param([int]$Lines = 400)
    # `-t` reads only the buffer's tail: cheap enough to poll with inside a row's window, and it never truncates adb.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & adb -s $DeviceSerial logcat -d -v time -t $Lines 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "adb logcat tail failed with exit code $code" }
    if ($null -eq $raw) { return @() }
    return @($raw)
}

function Get-EdithDeviceTimestamp {
    # The device's own wall clock, in the exact shape `logcat -t '<time>'` accepts (MM-DD HH:MM:SS.mmm).
    #
    # Read FROM the device rather than from the host: `logcat -t` is compared against the timestamps logcat itself
    # prints, so a host clock a few seconds off would silently move the window.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & adb -s $DeviceSerial shell "date '+%m-%d %H:%M:%S.000'" 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "adb shell date failed with exit code $code" }
    $stamp = (@($raw) | Where-Object { $_ } | Select-Object -First 1)
    if (-not $stamp) { throw "adb shell date returned nothing" }
    return $stamp.Trim()
}

function Get-EdithLogcatSince {
    param([Parameter(Mandatory = $true)][string]$Since)
    # Only the lines **since the row started**, asked of logcat itself.
    #
    # This replaces "read the whole buffer, then slice from the previous row's line count" (review of the W4 run).
    # Index slicing was wrong twice over:
    #   * it read the ENTIRE buffer on every 3 s poll. Measured on the K40: 111,658 lines held, so a 16 s window moved
    #     ~5x that per row - which is where the ~75 s per row went (the gap is only 61 s of it);
    #   * it broke whenever the ring buffer **rotated** between two reads. The count then does not grow, the slice comes
    #     back empty, and the row is recorded `harness_error / no_inject_end_in_window` with `logLines=0` - 50 of 171
    #     rows in the W4 run, which is an instrument outcome, not a measurement.
    # This project had already learned this ("a logcat window compared by index is defeated by buffer rotation; filter by
    # timestamp") and the campaign had not been changed to match. `-t <time>` cannot rotate: the buffer either still has
    # the window or the window is genuinely gone, and a row's window is seconds old against an ~11 MiB buffer.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & adb -s $DeviceSerial logcat -d -v time -t $Since 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "adb logcat -t failed with exit code $code" }
    if ($null -eq $raw) { return @() }
    return @($raw)
}

function Invoke-EdithAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    # Same reason as above: adb's progress notices arrive on stderr and must not abort the run.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & adb -s $DeviceSerial @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ ExitCode = $code; Output = @($output) }
}

# =====================================================================================
# Reporting (all console output is ASCII: the corpus text never reaches the console)
# =====================================================================================

function Write-EdithScenarioCounts {
    param($Rows, [string]$Indent = '  ')
    foreach ($scenario in $CorpusScenarios) {
        $pos = @($Rows | Where-Object { $_.Scenario -eq $scenario -and $_.Label -eq 'positive' }).Count
        $neg = @($Rows | Where-Object { $_.Scenario -eq $scenario -and $_.Label -eq 'negative' }).Count
        if (($pos + $neg) -eq 0) { continue }
        Write-Host ("{0}{1}: positive={2} negative={3} subtotal={4}" -f $Indent, $scenario, $pos, $neg, ($pos + $neg))
    }
    $tp = @($Rows | Where-Object { $_.Label -eq 'positive' }).Count
    $tn = @($Rows | Where-Object { $_.Label -eq 'negative' }).Count
    Write-Host ("{0}TOTAL: positive={1} negative={2} rows={3}" -f $Indent, $tp, $tn, ($tp + $tn))
}

# =====================================================================================
# Main
# =====================================================================================

if ($Script:EdithLibraryOnly) { return }

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
function Resolve-EdithPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $repoRoot $Path)
}

$corpusFull = Resolve-EdithPath $CorpusPath
$obsFull = Resolve-EdithPath $ObservationsPath
$audioFull = Resolve-EdithPath $AudioDir
$evidenceFull = [System.IO.Path]::ChangeExtension($obsFull, $null).TrimEnd('.') + '-evidence.txt'

$DeviceSerial = '2696813b'   # redmi K40, the delivery target (AGENTS.md)

if (-not (Test-Path $corpusFull)) { throw "corpus not found: $corpusFull" }
if ($WaitSeconds -lt 1) { throw "-WaitSeconds must be >= 1 (got $WaitSeconds)" }
if ($Limit -lt 0) { throw "-Limit must be >= 0 (0 means no limit), got $Limit" }

$scenarioFilter = ConvertTo-EdithFilter $Scenario
$labelFilter = ConvertTo-EdithFilter $Label
foreach ($s in $scenarioFilter) {
    if ($CorpusScenarios -notcontains $s) { throw "-Scenario '$s' is not one of: $($CorpusScenarios -join ', ')" }
}
foreach ($l in $labelFilter) {
    if ($CorpusLabels -notcontains $l) { throw "-Label '$l' is not one of: $($CorpusLabels -join ', ')" }
}

# --- 1. parse -------------------------------------------------------------------------
$markdown = [System.IO.File]::ReadAllText($corpusFull, [System.Text.UTF8Encoding]::new($false))
$allRows = @(ConvertTo-EdithCorpusRow -Markdown $markdown)
if ($allRows.Count -eq 0) { throw "no corpus rows parsed from $corpusFull - refusing to run an empty campaign" }

$selected = @($allRows)
if ($scenarioFilter.Count -gt 0) { $selected = @($selected | Where-Object { $scenarioFilter -contains $_.Scenario }) }
if ($labelFilter.Count -gt 0) { $selected = @($selected | Where-Object { $labelFilter -contains $_.Label }) }
if ($Limit -gt 0 -and $selected.Count -gt $Limit) { $selected = @($selected[0..($Limit - 1)]) }

if (-not $DryRun) {
    $obsDir = Split-Path -Parent $obsFull
    if ($obsDir -and -not (Test-Path $obsDir)) { New-Item -ItemType Directory -Path $obsDir -Force | Out-Null }
}

Write-Host "== P3.4 trigger campaign =="
Write-Host ("mode            : " + $(if ($DryRun) { 'DRY RUN (no adb, no synthesis, no writes)' } else { 'LIVE' }))
Write-Host ("corpus          : " + $corpusFull)
Write-Host ("observations    : " + $obsFull)
Write-Host ("evidence        : " + $evidenceFull)
Write-Host ("audio dir       : " + $audioFull)
Write-Host ("device dir      : " + $DeviceDir)
Write-Host ("device serial   : " + $DeviceSerial)
Write-Host ("filters         : scenario=[" + ($scenarioFilter -join ',') + "] label=[" + ($labelFilter -join ',') + "]")
Write-Host ("waitSeconds     : " + $WaitSeconds)
Write-Host ("limit           : " + $Limit)
Write-Host ''
Write-Host ("parsed corpus rows : " + $allRows.Count)
Write-Host ("scorer-visible rows: " + (Get-EdithScorerRowCount -Markdown $markdown) + "   (rows the scorer's parseLabels keeps; campaign rows must be a subset)")
Write-EdithScenarioCounts -Rows $allRows
Write-Host ''
Write-Host ("selected by filters: " + $selected.Count)
Write-EdithScenarioCounts -Rows $selected

# --- 2. resume ------------------------------------------------------------------------
# A row is "settled" when it already has a real verdict (`triggered`, or `none` from a decision the
# decider really made). A `harness_error` line is deliberately NOT settled: it records that the row
# never got a fair run, so the default behaviour is to try it again.
#
# W2/F19 widened what lands in that class: a window with **no decision line at all**, and a window
# whose only decision was a **COOLDOWN suppression**, are both `harness_error` now (they used to be
# written `none`). That is what makes the ids in the batch report's RE-RUN REQUIRED list actionable -
# otherwise the obvious re-run would silently skip exactly the rows that need re-running, and the
# scorer would quietly score an instrument outcome as a model "no".
$existingVerdicts = @{}
if (Test-Path $obsFull) {
    $existingVerdicts = Get-EdithObservationVerdict -Lines ([System.IO.File]::ReadAllLines($obsFull, [System.Text.UTF8Encoding]::new($false)))
}
$alreadySettled = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($id in $existingVerdicts.Keys) {
    if ($Force) { continue }
    if ($existingVerdicts[$id] -eq 'harness_error') { continue }
    [void]$alreadySettled.Add($id)
}
$retryIds = New-Object System.Collections.ArrayList
foreach ($id in $existingVerdicts.Keys) { if ($existingVerdicts[$id] -eq 'harness_error') { [void]$retryIds.Add($id) } }
$toRun = New-Object System.Collections.ArrayList
$skippedAlreadyRun = New-Object System.Collections.ArrayList
foreach ($row in $selected) {
    if ($alreadySettled.Contains($row.Id)) { [void]$skippedAlreadyRun.Add($row.Id); continue }
    [void]$toRun.Add($row)
}
Write-Host ''
Write-Host ("observation lines on file        : " + $existingVerdicts.Count + $(if ($Force) { ' (all ignored: -Force)' } else { '' }))
Write-Host ("settled (won't re-run)           : " + $alreadySettled.Count)
Write-Host ("harness_error on file (re-runs)  : " + $retryIds.Count)
if ($retryIds.Count -gt 0 -and $retryIds.Count -le 20) {
    Write-Host ("  " + (($retryIds | Sort-Object) -join ' '))
}
Write-Host ("skipped as already run           : " + $skippedAlreadyRun.Count)
if ($skippedAlreadyRun.Count -gt 0 -and $skippedAlreadyRun.Count -le 20) {
    Write-Host ("  " + ($skippedAlreadyRun -join ' '))
}

# --- 3. audio -------------------------------------------------------------------------
$withAudio = New-Object System.Collections.ArrayList
$missingAudio = New-Object System.Collections.ArrayList
foreach ($row in $toRun) {
    $wav = Join-Path $audioFull ($row.Id + '.wav')
    if (Test-Path $wav) { [void]$withAudio.Add($row) } else { [void]$missingAudio.Add($row) }
}
$synthesized = New-Object System.Collections.ArrayList
if ($missingAudio.Count -gt 0) {
    Write-Host ''
    Write-Host ("WAV present: " + $withAudio.Count + "  missing: " + $missingAudio.Count)
    if ($DryRun) {
        Write-Host "  DRY RUN: would synthesize these (with -Synthesize):"
        foreach ($row in $missingAudio) { Write-Host ("    " + $row.Id + " -> " + (Join-Path $audioFull ($row.Id + '.wav'))) }
    } elseif ($Synthesize) {
        $synthScript = Join-Path $PSScriptRoot $SynthScriptName
        if (-not (Test-Path $synthScript)) { throw "missing synthesizer script: $synthScript" }
        $manifestDir = Join-Path $repoRoot '.local\p3-4'
        if (-not (Test-Path $manifestDir)) { New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null }
        $manifestPath = Join-Path $manifestDir 'campaign-synth-manifest.tsv'
        if (-not (Test-Path $audioFull)) { New-Item -ItemType Directory -Path $audioFull -Force | Out-Null }
        $manifestLines = New-Object System.Collections.ArrayList
        [void]$manifestLines.Add('# id<TAB>text - written by campaign.ps1, UTF-8, read back with an explicit UTF-8 decoder')
        foreach ($row in $missingAudio) { [void]$manifestLines.Add($row.Id + "`t" + $row.Text) }
        [System.IO.File]::WriteAllLines($manifestPath, $manifestLines.ToArray(), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ("  synthesizing " + $missingAudio.Count + " clip(s) via " + $SynthScriptName)
        $synthOut = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $synthScript -ManifestPath $manifestPath -Dir $audioFull 2>&1
        $synthCode = $LASTEXITCODE
        foreach ($line in @($synthOut)) { Write-Host ("    " + $line) }
        if ($synthCode -ne 0) { throw "synthesizer failed with exit code $synthCode" }
        foreach ($row in $missingAudio) {
            $wav = Join-Path $audioFull ($row.Id + '.wav')
            if (Test-Path $wav) { [void]$withAudio.Add($row); [void]$synthesized.Add($row.Id) }
            else { Write-Host ("    SYNTH MISSING: " + $row.Id) }
        }
        $missingAudio = @($missingAudio | Where-Object { $synthesized -notcontains $_.Id })
    } else {
        Write-Host "  -Synthesize not given: these rows will NOT be run (no fabricated audio)."
    }
}

$runRows = @($withAudio)
Write-Host ''
Write-Host ("rows to run this batch: " + $runRows.Count)
Write-EdithScenarioCounts -Rows $runRows

# --- 4. the plan ----------------------------------------------------------------------
if ($DryRun) {
    Write-Host ''
    Write-Host "PLANNED PER-ROW ACTIONS (dry run; nothing is executed):"
    foreach ($row in $runRows) {
        $wav = Join-Path $audioFull ($row.Id + '.wav')
        Write-Host ("  " + $row.Id + " [" + $row.Scenario + "/" + $row.Label + "]")
        Write-Host ("    text chars=" + $row.Text.Length + "  wav=" + $wav)
        Write-Host ("    push  : adb -s " + $DeviceSerial + " push <wav> " + ($DeviceDir.TrimEnd('/') + '/' + $row.Id + '.wav'))
        Write-Host ("    inject: adb -s " + $DeviceSerial + " shell `"am broadcast -a " + $InjectAction + " -n " + $InjectComponent + " --es path " + ($DeviceDir.TrimEnd('/') + '/' + $row.Id + '.wav') + "`"")
        Write-Host ("    wait  : " + $WaitSeconds + " s, then adb -s " + $DeviceSerial + " logcat -d -v time -t <row start> (this row's window only)")
        Write-Host ("    record: " + $row.Id + "=<triggered|none|harness_error> -> " + $obsFull)
    }
    if ($runRows.Count -eq 0) { Write-Host '  (nothing to run)' }
    Write-Host ''
    Write-Host ("DRY RUN summary: parsed=" + $allRows.Count + " selected=" + $selected.Count + " alreadyRun=" + $skippedAlreadyRun.Count + " missingAudio=" + @($missingAudio).Count + " wouldRun=" + $runRows.Count)
    Write-Host ''
    Write-Host "SCORING COMMAND (run after the live campaign):"
    Write-Host ("  " + "& .\.superpowers\sdd\2026-10-08-p3-0-probes\gradle.ps1 p34-eval :tools:p3-eval-trigger:run --args=`"$CorpusPath $ObservationsPath 10`"")
    return
}

# --- 5. live run ----------------------------------------------------------------------
if ($runRows.Count -eq 0) {
    Write-Host ''
    Write-Host "nothing to run: every selected row is already settled or has no WAV. No device contact."
    return
}

$adbProbe = Invoke-EdithAdb -Arguments @('get-state')
if ($adbProbe.ExitCode -ne 0 -or ($adbProbe.Output -join ' ').Trim() -ne 'device') {
    throw ("device " + $DeviceSerial + " is not reachable: " + (($adbProbe.Output -join ' ').Trim()))
}

try { & adb -s $DeviceSerial shell "mkdir -p $DeviceDir" | Out-Null } catch { }
$pushedIds = New-Object 'System.Collections.Generic.HashSet[string]'

Write-EdithLine -Path $evidenceFull -Text ("# campaign start " + (Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK') +
    " corpus=" + $CorpusPath + " rows=" + $runRows.Count + " waitSeconds=" + $WaitSeconds +
    " scenario=[" + ($scenarioFilter -join ',') + "] label=[" + ($labelFilter -join ',') + "]")
Write-EdithLine -Path $evidenceFull -Text "# evidence carries ids, device timestamps, criterion lines and counts only - never corpus text or model output"

$counts = @{ triggered = 0; none = 0; harness_error = 0 }
$noDecision = 0
$cooldownSuppressed = 0
$harnessErrors = New-Object System.Collections.ArrayList
$earlyWindows = New-Object System.Collections.ArrayList
$abortedDead = $false
$index = 0
foreach ($row in $runRows) {
    $index++
    $remote = $DeviceDir.TrimEnd('/') + '/' + $row.Id + '.wav'
    $wav = Join-Path $audioFull ($row.Id + '.wav')
    Write-Host ("[" + $index + "/" + $runRows.Count + "] " + $row.Id + " (" + $row.Scenario + "/" + $row.Label + ")")

    # Spacing first, injection second: the gap has to sit between two utterances, not between an utterance and its window.
    if ($GapSeconds -gt 0) { Start-Sleep -Seconds $GapSeconds }

    # This row's window starts here, on the DEVICE clock, and every read below is bounded by it (see Get-EdithLogcatSince).
    # Taken after the gap and before the injection so the window holds this row's utterance and nothing older.
    $windowStart = Get-EdithDeviceTimestamp

    if (-not $pushedIds.Contains($row.Id)) {
        $push = Invoke-EdithAdb -Arguments @('push', $wav, $remote)
        if ($push.ExitCode -ne 0) {
            $counts.harness_error++
            [void]$harnessErrors.Add($row.Id)
            Write-EdithLine -Path $evidenceFull -Text ($row.Id + "`tverdict=harness_error`treason=adb_push_failed exit=" + $push.ExitCode +
                "`t" + (($push.Output -join ' | ')))
            Write-EdithLine -Path $obsFull -Text ($row.Id + "=harness_error")
            Write-Host "  harness_error: push failed"
            continue
        }
        [void]$pushedIds.Add($row.Id)
    }

    $broadcast = Invoke-EdithAdb -Arguments @('shell', "am broadcast -a $InjectAction -n $InjectComponent --es path $remote")
    if ($broadcast.ExitCode -ne 0) {
        $counts.harness_error++
        [void]$harnessErrors.Add($row.Id)
        Write-EdithLine -Path $evidenceFull -Text ($row.Id + "`tverdict=harness_error`treason=am_broadcast_failed exit=" + $broadcast.ExitCode +
            "`t" + (($broadcast.Output -join ' | ')))
        Write-EdithLine -Path $obsFull -Text ($row.Id + "=harness_error")
        Write-Host "  harness_error: broadcast failed"
        continue
    }

    # Poll instead of sleeping the whole window. A row is settled the moment **this row's** window contains a decision
    # (`P3_PROACTIVE skip reason=...` or `P3_PROACTIVE request`), and the remaining wait only slows the campaign down.
    #
    # The window is bounded by **device timestamp**, not by a line-count baseline. The earlier index-based version had to
    # be index-based for a real reason - reading the device tail alone sees the *previous* row's decision line and exits
    # instantly, which silently packed rows ~5 s apart and let the product's cooldown decide most of them (measured:
    # config B `COOLDOWN=62 / SHORT=32`) - but it also read the whole 111k-line buffer per poll and broke on buffer
    # rotation. `-t $windowStart` keeps the "only this row's lines" property without either cost.
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $chainSettle = 0
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $peek = @()
        try { $peek = @(Get-EdithLogcatSince -Since $windowStart) } catch { }
        if (@($peek | Where-Object { $_ -match 'P3_PROACTIVE (skip reason=|request)' }).Count -gt 0) {
            # A triggering row is NOT settled yet: the model call, the hint and its display ack come ~0.2-2 s later
            # (review I1, second half). Capturing here is why the artifact held `request` and no `P3_HTTP`/`P3_HINT`.
            $chainSettle = Get-EdithChainSettleSeconds -PeekLines $peek
            break
        }
    }
    if ($chainSettle -gt 0) { Start-Sleep -Seconds $chainSettle }

    $captureFailed = $false
    $after = @()
    try { $after = @(Get-EdithLogcatSince -Since $windowStart) } catch { $captureFailed = $true }
    $tail = $after

    # Stop a batch the app is not processing at all, at row 3 instead of after 210 rows (review I2). The rows already
    # written are `harness_error`, so nothing is lost - the next run retries them once the session is running.
    if ($index -le 3) {
        [void]$earlyWindows.Add(($tail -join "`n"))
        if ($index -eq 3 -and (Test-EdithBatchIsDead -RowWindows $earlyWindows)) {
            Write-Host ''
            Write-Host "ABORT: no criterion line in three consecutive windows - the app is not processing audio."
            Write-Host "       Start a session (tap START), confirm a P3_AUDIO state=armed line in logcat, then re-run:"
            Write-Host "       these rows are harness_error, so the next run retries them without -Force."
            Write-EdithLine -Path $evidenceFull -Text ("# batch ABORTED at row " + $index +
                ": no criterion line in three consecutive windows (is the session running?)")
            $abortedDead = $true
            break
        }
    }

    # Verdict rules, in order (W2/F19: instrument outcomes are unresolved, never a model "no"):
    #   empty/failed capture            -> harness_error
    #   no injection completion in tail -> harness_error (the row was never actually played)
    #   any COOLDOWN suppression        -> harness_error (the instrument's own spacing silenced the row)
    #   no decision line at all         -> harness_error (nothing was decided; the old `none` hid this)
    #   P3_PROACTIVE request (ctx=...)   -> triggered
    #   any other decision line         -> none (the decider really did answer)
    if ($captureFailed -or $after.Count -eq 0) {
        $verdict = 'harness_error'
        $reason = 'logcat_empty_or_failed'
    } else {
        $verdictInfo = Get-EdithRowVerdict -Tail $tail
        $verdict = $verdictInfo.Verdict
        $reason = $verdictInfo.Reason
        # Both counters are scoped to rows that are actually UNRESOLVED. Counting a `triggered` window's stray COOLDOWN
        # line here (review I3) would put a delivered hint under a line labelled "unresolved, re-run".
        if ($verdict -eq 'harness_error' -and $reason -eq 'no_decision_line_in_window') { $noDecision++ }
        if ($verdict -eq 'harness_error' -and $verdictInfo.CooldownSuppressed) { $cooldownSuppressed++ }
    }

    $evidence = @(Select-EdithEvidenceLine -Tail $tail)
    $counts[$verdict]++
    if ($verdict -eq 'harness_error') { [void]$harnessErrors.Add($row.Id) }

    foreach ($line in $evidence) {
        Write-EdithLine -Path $evidenceFull -Text ($row.Id + "`t" + (Get-EdithLogTime -Line $line) + "`t" + $line)
    }
    # The **verdict**, not the literal `none` (review I3): a row with no criterion line and a `harness_error` verdict used
    # to be recorded here as `none`, so the audited artifact said "the model said no" for an instrument outcome - the one
    # thing W2 exists to stop, still visible in the file the report cites.
    Write-EdithLine -Path $evidenceFull -Text ($row.Id + "`t" + $verdict + "`t" + $reason +
        "`tnoDecisionLine=" + ($reason -eq 'no_decision_line_in_window') + "`tcriteria=" + $evidence.Count +
        "`tlogLines=" + $tail.Count)

    # The observation line is written LAST and alone, so it is never half-written.
    Write-EdithLine -Path $obsFull -Text ($row.Id + "=" + $verdict)
    Write-Host ("  " + $verdict + " (" + $reason + ")")
}

# --- 6. batch report ------------------------------------------------------------------
Write-Host ''
Write-Host "== batch report =="
if ($abortedDead) {
    Write-Host "BATCH ABORTED: three consecutive windows carried no criterion line of any kind, so the app was not"
    Write-Host "processing audio (a stopped session looks exactly like a product that never decides). Start a session"
    Write-Host "and re-run: every row written so far is harness_error and is retried without -Force."
}
Write-Host "RECORDED TRIGGERS ARE A LOWER BOUND: a row with no log line proves nothing, and any 'skip reason=DEDUP'"
Write-Host "line proves an earlier hint that was NOT recorded (noteDedup sits next to noteRequest in the success branch)."
Write-Host ("rows run        : " + $runRows.Count)
Get-EdithBatchReportLines -Triggered $counts.triggered -None $counts.none -HarnessErrors $counts.harness_error `
    -NoDecision $noDecision -Cooldown $cooldownSuppressed | ForEach-Object { Write-Host $_ }
Write-Host ("synthesized now : " + $synthesized.Count)
Write-Host ''
Write-Host "triggered by scenario/label (cumulative, read back from the observations file):"
$verdicts = Get-EdithObservationVerdict -Lines ([System.IO.File]::ReadAllLines($obsFull, [System.Text.UTF8Encoding]::new($false)))
foreach ($scenario in $CorpusScenarios) {
    foreach ($label in $CorpusLabels) {
        $group = @($allRows | Where-Object { $_.Scenario -eq $scenario -and $_.Label -eq $label })
        if ($group.Count -eq 0) { continue }
        $settled = 0
        $t = 0
        foreach ($row in $group) {
            if ($verdicts.ContainsKey($row.Id)) {
                $settled++
                if ($verdicts[$row.Id] -eq 'triggered') { $t++ }
            }
        }
        Write-Host ("  " + $scenario + "/" + $label + ": corpus=" + $group.Count + " settled=" + $settled + " triggered=" + $t)
    }
}

if ($harnessErrors.Count -gt 0) {
    Write-Host ''
    Write-Host "RE-RUN REQUIRED (harness_error - not a model verdict, the row never got a fair run):"
    Write-Host ("  " + ($harnessErrors -join ' '))
    Write-Host "  re-run: powershell -NoProfile -ExecutionPolicy Bypass -File tools\p3-eval-trigger\campaign.ps1"
    Write-Host "  No -Force needed: harness_error rows are not treated as settled, so the next run retries them."
    Write-Host "  -Limit skips to the first N selected rows, so re-run WITHOUT -Limit (or add -Limit only to pilot)."
} else {
    Write-Host ''
    Write-Host "no harness_error this batch"
}

$scoreCmd = "& .\.superpowers\sdd\2026-10-08-p3-0-probes\gradle.ps1 p34-eval :tools:p3-eval-trigger:run --args=`"$CorpusPath $ObservationsPath 10`""
Write-Host ''
Write-Host "SCORING COMMAND (copy as-is; windowMinutes defaults to 10 in the CLI):"
Write-Host ("  " + $scoreCmd)
Write-Host ''
Write-Host "NOTE: 'triggered' and 'none' are the only scored verdicts. A harness_error row is UNRESOLVED: it is not"
Write-Host "      counted as a model silence, and the scoring CLI refuses to print a score while any remain (exit 2;"
Write-Host "      --allow-errors overrides). Re-run them above BEFORE quoting a score, and give the chat subset its own"
Write-Host "      run (default 60 s cooldown window)."
try {
    Invoke-Expression $scoreCmd
} catch {
    Write-Host ("scoring CLI failed: " + $_.Exception.Message)
    Write-Host ("run it by hand: " + $scoreCmd)
}
