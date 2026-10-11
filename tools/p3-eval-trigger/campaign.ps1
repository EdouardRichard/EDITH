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
$TriggerMarker = 'P3_PROACTIVE request='
$CooldownMarker = 'P3_PROACTIVE skip reason=COOLDOWN'
$NoiseMarkers = @('P3_PROACTIVE skip', 'P3_ACOUSTIC', 'P3_SCRIPT', 'P3_ASR')
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
# to print "none : 10" followed by an indented "of which WITHOUT a decision line … : 1", while that row was in fact one
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

function Get-EdithLogcat {
    # The FULL buffer, read with -d. Never pipe this into Select-Object -First: that closes
    # the pipe under a live process and adb dies with it.
    # Function-scoped preference: `adb` writes progress to stderr, and this script runs with 'Stop', which would turn
    # a normal "1 file pushed" notice into a terminating NativeCommandError. The exit code is the verdict.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & adb -s $DeviceSerial logcat -d -v time 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { throw "adb logcat failed with exit code $code" }
    if ($null -eq $raw) { return @() }
    return @($raw)
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

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
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
        Write-Host ("    wait  : " + $WaitSeconds + " s, then adb -s " + $DeviceSerial + " logcat -d -v time (full buffer, filtered in memory)")
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

$baseline = @(Get-EdithLogcat)
Write-Host ''
Write-Host ("logcat baseline lines: " + $baseline.Count)

$counts = @{ triggered = 0; none = 0; harness_error = 0 }
$noDecision = 0
$cooldownSuppressed = 0
$harnessErrors = New-Object System.Collections.ArrayList
$index = 0
foreach ($row in $runRows) {
    $index++
    $remote = $DeviceDir.TrimEnd('/') + '/' + $row.Id + '.wav'
    $wav = Join-Path $audioFull ($row.Id + '.wav')
    Write-Host ("[" + $index + "/" + $runRows.Count + "] " + $row.Id + " (" + $row.Scenario + "/" + $row.Label + ")")

    # Spacing first, injection second: the gap has to sit between two utterances, not between an utterance and its window.
    if ($GapSeconds -gt 0) { Start-Sleep -Seconds $GapSeconds }

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
    # (`P3_PROACTIVE skip reason=` or `request=`), and the remaining wait only slows the campaign down.
    #
    # The poll compares against the row's own baseline by **index**, exactly like the verdict below: reading the device
    # tail alone would see the *previous* row's decision line and exit instantly, which silently packed the rows ~5 s
    # apart and made the product's cooldown - not the gate under test - decide most of them. That bug was measured
    # (config B: `COOLDOWN=62 / SHORT=32`) before it was fixed, and it is why this loop is index-based.
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $peek = @()
        try { $peek = @(Get-EdithLogcat) } catch { }
        if ($peek.Count -le $baseline.Count) { continue }
        $peekTail = @($peek[$baseline.Count..($peek.Count - 1)])
        if (@($peekTail | Where-Object { $_ -match 'P3_PROACTIVE (skip reason=|request=)' }).Count -gt 0) { break }
    }

    $captureFailed = $false
    $after = @()
    try { $after = @(Get-EdithLogcat) } catch { $captureFailed = $true }
    $tail = @()
    if (-not $captureFailed -and $after.Count -gt $baseline.Count) { $tail = @($after[$baseline.Count..($after.Count - 1)]) }

    # Verdict rules, in order (W2/F19: instrument outcomes are unresolved, never a model "no"):
    #   empty/failed capture            -> harness_error
    #   no injection completion in tail -> harness_error (the row was never actually played)
    #   any COOLDOWN suppression        -> harness_error (the instrument's own spacing silenced the row)
    #   no decision line at all         -> harness_error (nothing was decided; the old `none` hid this)
    #   P3_PROACTIVE request=           -> triggered
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

    $baseline = $after
}

# --- 6. batch report ------------------------------------------------------------------
Write-Host ''
Write-Host "== batch report =="
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
