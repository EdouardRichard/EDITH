# Tests for campaign.ps1's corpus parser.
#
# The parser is the one place in the campaign where a wrong answer is silent: a row that is
# parsed but not scoreable gets *run* on the device and then *ignored* by the scorer (which
# only accepts rows whose cells[1] is one of the four scenarios), and a row that is scoreable
# but not parsed is scored as "not triggered" without ever being played. Both flatter or
# deflate the metric without any error message. So the parser is pinned here against the real
# frozen corpus, and against decoys that the scorer's own parser would also reject.
#
# Run: pwsh -NoProfile -File tools\p3-eval-trigger\CampaignParser.Tests.ps1
# ASCII-only source, like campaign.ps1 (see the note there).
$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $here '..\..')).Path
# Passing the switch in the dot-source line is what makes the guard fire (campaign.ps1 reads
# it off the caller's command text; see the note there).
. (Join-Path $here 'campaign.ps1') -EdithLibraryOnly

$failures = New-Object System.Collections.ArrayList
$checks = 0

function Assert-Equal($expected, $actual, $name) {
    $script:checks++
    if ("$expected" -ne "$actual") {
        [void]$script:failures.Add("FAIL $name : expected [$expected] actual [$actual]")
    } else {
        Write-Host ("ok   {0} = {1}" -f $name, $actual)
    }
}

# --- decoys: every line here must NOT become a campaign row -------------------------------
$decoy = @(
    '| id | scenario | label | text | note |',
    '|---|---|---|---|---|',
    '| knowledge | 30 | 30 | 60 |',
    '| **total** | **90** | **120** | **210** |',
    '| K-P-01 | smalltalk | positive | hello | not one of the four scenarios |',
    '| K-P-01 | knowledge | maybe | hello | label is not positive/negative |',
    '| K-P-01 | knowledge | positive | only four cells |',
    '| K-P-01-K-P-01 | knowledge | positive | id is not the corpus shape |',
    'not a table row',
    ''
) -join "`r`n"
$rows = ConvertTo-EdithCorpusRow -Markdown $decoy
Assert-Equal 0 $rows.Count 'decoy lines parsed'

# --- real corpus: counts must match the frozen table --------------------------------------
$corpusPath = Join-Path $repoRoot 'docs\project\evidence\2026-10-11-p3-4-eval-corpus.md'
$markdown = [System.IO.File]::ReadAllText($corpusPath, [System.Text.UTF8Encoding]::new($false))
$rows = ConvertTo-EdithCorpusRow -Markdown $markdown
Assert-Equal 210 $rows.Count 'frozen corpus rows'
Assert-Equal 210 ($rows | Select-Object -ExpandProperty Id -Unique).Count 'ids unique'

foreach ($case in @(
        @{ scenario = 'knowledge'; label = 'positive'; count = 30 },
        @{ scenario = 'knowledge'; label = 'negative'; count = 30 },
        @{ scenario = 'privacy'; label = 'positive'; count = 30 },
        @{ scenario = 'privacy'; label = 'negative'; count = 30 },
        @{ scenario = 'dilemma'; label = 'positive'; count = 30 },
        @{ scenario = 'dilemma'; label = 'negative'; count = 30 },
        @{ scenario = 'chat'; label = 'negative'; count = 30 })) {
    $n = ($rows | Where-Object { $_.Scenario -eq $case.scenario -and $_.Label -eq $case.label }).Count
    Assert-Equal $case.count $n ("{0}/{1}" -f $case.scenario, $case.label)
}

# --- the campaign's row set must be a SUBSET of the rows the scorer accepts ----------------
# The scorer keeps any table row with >=5 cells whose cell[1] is a scenario and cell[2] is a
# label; the campaign additionally requires the id shape. A subset means every row the
# campaign runs is scored, and no campaign row is silently dropped by the scorer.
$scorerRows = New-Object System.Collections.ArrayList
foreach ($line in ($markdown -split "`r?`n")) {
    $trimmed = $line.Trim()
    if (-not $trimmed.StartsWith('|')) { continue }
    $cells = @($trimmed.Trim('|') -split '\|' | ForEach-Object { $_.Trim() })
    if ($cells.Count -lt 5) { continue }
    if ($cells[1] -notin @('knowledge', 'privacy', 'dilemma', 'chat')) { continue }
    if ($cells[2] -ne 'positive' -and $cells[2] -ne 'negative') { continue }
    [void]$scorerRows.Add($cells)
}
Assert-Equal 210 $scorerRows.Count 'scorer-visible rows'
$scorerIds = @($scorerRows | ForEach-Object { $_[0] })
$outside = @($rows | Where-Object { $scorerIds -notcontains $_.Id })
Assert-Equal 0 $outside.Count 'campaign rows outside the scorer row set'

# --- text is carried verbatim (the WAV must say what the corpus says) ----------------------
# The invariant that matters is that the text cell survives the parse whole and still is the
# Chinese sentence, not an ASCII placeholder or a truncated cell. Pinning an exact length
# would only re-assert the corpus's wording; this asserts the parse did not mangle it.
$sample = @($rows | Where-Object { $_.Id -eq 'K-P-01' })
Assert-Equal 1 $sample.Count 'K-P-01 present'
$sampleText = $sample[0].Text
$cjk = @($sampleText.ToCharArray() | Where-Object { [int]$_ -ge 0x4E00 -and [int]$_ -le 0x9FFF }).Count
Assert-Equal $true ($cjk -gt 0) 'K-P-01 text carries CJK'
Assert-Equal $true ($sampleText -notmatch '\|') 'K-P-01 text has no stray pipe'
$allTextsHaveCjk = @($rows | Where-Object {
        @($_.Text.ToCharArray() | Where-Object { [int]$_ -ge 0x4E00 -and [int]$_ -le 0x9FFF }).Count -eq 0
    }).Count
Assert-Equal 0 $allTextsHaveCjk 'rows whose text has no CJK'
$repeated = @($rows | Group-Object Text | Where-Object { $_.Count -gt 1 }).Count
Assert-Equal 0 $repeated 'rows sharing the same text'

# --- subset filters -----------------------------------------------------------------------
$chat = @($rows | Where-Object { $_.Scenario -eq 'chat' })
Assert-Equal 30 $chat.Count 'chat rows'
$neg = @($rows | Where-Object { $_.Label -eq 'negative' })
Assert-Equal 120 $neg.Count 'negative rows'

# --- observations / resume semantics -------------------------------------------------------
# The scorer reads any value that is not exactly `triggered` as "none". So the campaign must
# never treat a `harness_error` line as a finished row: a harness failure scored as a model
# "no" is exactly the false negative that flatters nothing and hides a broken run.
$verdicts = Get-EdithObservationVerdict -Lines @(
    '# comment line', '', '  ', 'K-P-01=triggered', 'K-P-02=none', 'K-P-03=harness_error', 'garbage no equals sign'
)
Assert-Equal 3 $verdicts.Count 'observation lines parsed'
Assert-Equal 'triggered' $verdicts['K-P-01'] 'K-P-01 verdict'
Assert-Equal 'none' $verdicts['K-P-02'] 'K-P-02 verdict'
Assert-Equal 'harness_error' $verdicts['K-P-03'] 'K-P-03 verdict'
Assert-Equal $false ($verdicts.ContainsKey('garbage')) 'garbage line ignored'
# A later line for the same id wins (append-only file: the last line is the current state).
$latest = Get-EdithObservationVerdict -Lines @('K-P-04=harness_error', 'K-P-04=triggered')
Assert-Equal 'triggered' $latest['K-P-04'] 'last line wins'

# --- filters -------------------------------------------------------------------------------
Assert-Equal 1 (@(ConvertTo-EdithFilter 'knowledge').Count) 'single scenario filter'
Assert-Equal 2 (@(ConvertTo-EdithFilter 'knowledge, chat').Count) 'scenario filter with space'
Assert-Equal 0 (@(ConvertTo-EdithFilter '').Count) 'empty filter means no filter'
Assert-Equal 0 (@(ConvertTo-EdithFilter '   ').Count) 'whitespace filter means no filter'
Assert-Equal 'knowledge' (@(ConvertTo-EdithFilter 'Knowledge'))[0] 'filter is lowercased'

# --- row verdicts: an unresolved row must never be scored as a model "no" ------------------
# The old rule wrote `none` for a window with no decision line at all, and for a window whose only
# decision was a COOLDOWN suppression. Both are INSTRUMENT outcomes: the decider never got to
# answer. Scoring them as "the model said no" is what made P3.4's recall a floor (0%) rather than a
# measurement, so they are `harness_error` now - unresolved, and re-runnable.
$v = Get-EdithRowVerdict -Tail @('P3_INJECT state=end', 'P3_PROACTIVE skip reason=SHORT')
Assert-Equal 'none' $v.Verdict 'a real decider line is a none'
Assert-Equal $true $v.DecisionSeen 'decision seen'
Assert-Equal $false $v.CooldownSuppressed 'not a cooldown row'

$v = Get-EdithRowVerdict -Tail @('P3_INJECT state=end', 'P3_ACOUSTIC label=Speech')
Assert-Equal 'harness_error' $v.Verdict 'no decision line is unresolved, not a model no'
Assert-Equal 'no_decision_line_in_window' $v.Reason 'unresolved reason is named'
Assert-Equal $false $v.DecisionSeen 'no decision was seen'

$v = Get-EdithRowVerdict -Tail @('P3_INJECT state=end', 'P3_PROACTIVE skip reason=COOLDOWN')
Assert-Equal 'harness_error' $v.Verdict 'a cooldown-suppressed row is unresolved'
Assert-Equal 'instrument_cooldown_suppressed' $v.Reason 'cooldown reason is named'
Assert-Equal $true $v.CooldownSuppressed 'cooldown flagged'

$v = Get-EdithRowVerdict -Tail @('P3_INJECT state=end', 'P3_PROACTIVE skip reason=DEDUP')
Assert-Equal 'none' $v.Verdict 'dedup is still a decision (and DEDUP proves an earlier hint)'

$v = Get-EdithRowVerdict -Tail @('P3_PROACTIVE request ctx=3 chars=7')
Assert-Equal 'harness_error' $v.Verdict 'a trigger without the injection end is unresolved'
Assert-Equal 'no_inject_end_in_window' $v.Reason 'the missing injection end wins over a stray trigger'

$v = Get-EdithRowVerdict -Tail @('P3_INJECT state=end', 'P3_PROACTIVE request=')
Assert-Equal 'triggered' $v.Verdict 'a trigger inside a completed window is triggered'

# The instrument-suppressed count must be nameable on its own, or a naive re-run skips exactly the
# rows that need re-running (they are `harness_error`, so they are not treated as settled).
Assert-Equal 'cooldown-suppressed (unresolved, re-run): 3' (Get-EdithCooldownReportLine -Count 3) 'cooldown report line'

# --- a harness_error row must be re-runnable, and a real verdict must not be ------------------
# The resume logic skips anything that is not `harness_error`. The rows the new rule produces are
# `harness_error`, so a plain re-run retries exactly them - no -Force, no -Limit, no bookkeeping.
$verdicts = Get-EdithObservationVerdict -Lines @('K-P-01=none', 'K-P-02=harness_error', 'K-P-03=triggered')
$reRunnable = @($verdicts.Keys | Where-Object { $verdicts[$_] -eq 'harness_error' })
Assert-Equal 1 $reRunnable.Count 'exactly one row is re-runnable'
Assert-Equal 'K-P-02' $reRunnable[0] 'and it is the unresolved one'
Assert-Equal 'harness_error' $verdicts['K-P-02'] 'the unresolved row is recorded as such'
# A later line for the same id settles it (the file is append-only; the last line is current state).
$settled = Get-EdithObservationVerdict -Lines @('K-P-02=harness_error', 'K-P-02=triggered')
Assert-Equal 0 (@($settled.Keys | Where-Object { $settled[$_] -eq 'harness_error' })).Count 'a re-run settles the row'

if ($failures.Count -gt 0) {
    Write-Host ''
    $failures | ForEach-Object { Write-Host $_ }
    Write-Host ("FAILED {0} of {1} checks" -f $failures.Count, $checks)
    exit 1
}
Write-Host ("PASS {0} checks" -f $checks)
exit 0
