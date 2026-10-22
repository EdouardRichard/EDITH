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
$repoRoot = (Resolve-Path (Join-Path $here '..\..\..')).Path
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

# --- THE REAL LINE FORMAT (verbatim from the device) --------------------------------------------
# The assertion above uses a FABRICATED line ('P3_PROACTIVE request=') that matches the marker only because the marker
# was written the same wrong way. The product actually logs `P3_PROACTIVE request ctx=5 chars=7` - `ctx=` follows
# `request`; there is no `request=`. The marker `'P3_PROACTIVE request='` therefore matched NOTHING and every real
# trigger was scored "no decision line": the structural cause of P3.4's recorded recall 0, and the mechanism behind F19
# (the 9 `DEDUP` rows proving unrecorded successful hints - the clue was right, the marker was not).
# This is a verbatim device line (K40, 2026-10-12 01:38:17.723).
$realTrigger = '10-12 01:38:17.723 I/System.out(18428): EdithSmart P3_PROACTIVE request ctx=5 chars=7'
$realEnd = '10-12 01:38:19.500 I/EdithP3 (18428): P3_INJECT state=end chunks=120 accepted=120 drained=true'
Assert-Equal 'triggered' (Get-EdithRowVerdict -Tail @($realTrigger, $realEnd)).Verdict 'a REAL device trigger line must score as triggered'
Assert-Equal 1 (@(Select-EdithEvidenceLine -Tail @($realTrigger)).Count) 'the trigger line must be captured as evidence'
# A skip line must keep working too (its shape really does carry `reason=`), pinned alongside so a future change to one
# marker cannot silently break the other.
$realSkip = '10-12 01:38:17.723 I/System.out(18428): EdithSmart P3_PROACTIVE skip reason=VAGUE'
Assert-Equal 'none' (Get-EdithRowVerdict -Tail @($realSkip, $realEnd)).Verdict 'a real skip line is still a decision'

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

# --- review I3: instrument counts must not be nested under `none`, and a triggered row is never unresolved -----
# `CooldownSuppressed` is true for any window containing a COOLDOWN line, *including one that also triggered*.
# Counting such a row under a line labelled "unresolved, re-run" would report a delivered hint as an unresolved row,
# which is why the run loop scopes both counters to `harness_error` verdicts.
$v = Get-EdithRowVerdict -Tail @('P3_INJECT state=end', 'P3_PROACTIVE request=', 'P3_PROACTIVE skip reason=COOLDOWN')
Assert-Equal 'triggered' $v.Verdict 'a window that triggered is triggered'
Assert-Equal $true $v.CooldownSuppressed 'even though it also carries a cooldown line'

$report = @(Get-EdithBatchReportLines -Triggered 1 -None 10 -HarnessErrors 2 -NoDecision 1 -Cooldown 0)
Assert-Equal 0 (@($report | Where-Object { $_ -match 'of which' }).Count) 'no line nests an instrument outcome under none'
Assert-Equal 1 (@($report | Where-Object { $_ -match 'none\s+: 10' }).Count) 'the none line carries only its own count'
Assert-Equal 1 (@($report | Where-Object { $_ -match 'harness_error\s+: 2' }).Count) 'harness_error carries its own count'
Assert-Equal 1 (@($report | Where-Object { $_ -match 'of the harness_errors, WITHOUT a decision line' }).Count) 'the no-decision count is nested under harness_error'

# --- W4: the diagnosis can only work if its own line is captured as evidence ------------------
# `P3_ASR` is what makes "why did this row reach no decision" answerable. It was added to the plugin but NOT to the
# campaign's evidence markers, so the first W4 batch recorded zero asr lines and the diagnosis could only say UNKNOWN -
# the instrument was invisible to the harness that consumes it. This pins the marker list itself.
$evidence = @(Select-EdithEvidenceLine -Tail @(
    '10-11 19:07:26.163 I/EdithP3 (18428): P3_ASR revision=6 final=true chars=15 usable=true',
    '10-11 19:07:26.163 I/EdithP3 (18428): P3_ACOUSTIC label=Speech',
    '10-11 19:07:29.000 I/EdithP3 (18428): P3_PROACTIVE skip reason=VAGUE'))
Assert-Equal 3 $evidence.Count 'every criterion line the diagnosis needs is captured'
Assert-Equal 1 (@($evidence | Where-Object { $_ -match 'P3_ASR' }).Count) 'the asr trace is captured'

# --- review I1: the artifact must show that a recorded trigger became a SHOWN hint --------------------------
# A row whose window logs `request` and then `silence reason=NO_CALL` used to be indistinguishable, in the artifact,
# from one that displayed a hint: the causal chain P3.5-C cites (`request` -> `P3_HTTP 200` -> `hint` -> `ack
# Displayed`) could only be quoted from ad-hoc logcat, never from the frozen file the scorer reads. These five lines
# are the product's real shapes (ModelHttpLog.kt:29, SmartAssistantPlugin.kt:1057/1115/1155/1167).
$expectedMarkers = @(
    'P3_PROACTIVE skip', 'P3_PROACTIVE request', 'P3_PROACTIVE admit', 'P3_ACOUSTIC', 'P3_SCRIPT',
    'P3_ASR', 'P3_TRANSCRIPT',
    'P3_HTTP', 'P3_HINT', 'P3_PROACTIVE silence', 'P3_PROACTIVE hint',
    # `P3_INJECT` is how the instrument reports its OWN outcome: `state=start|refused|end`. Without it in the capture
    # list, an injection that was refused (`state=refused reason=NO_EPOCH`, the app restarted with no armed session)
    # is visible in the artifact only as "no inject end" - indistinguishable from a product that never decided. That
    # cost a diagnosis on 2026-10-12 04:35, found only by reading live logcat.
    'P3_INJECT')
foreach ($marker in $expectedMarkers) {
    # The expectation list is written out HERE rather than read from $NoiseMarkers: a loop over the list itself would
    # still pass after someone deleted a marker from it, which is exactly how P3_ASR went missing for a whole batch.
    Assert-Equal $true ($NoiseMarkers -contains $marker) ("the marker list keeps " + $marker)
    Assert-Equal 1 (@(Select-EdithEvidenceLine -Tail @("10-12 02:29:56.420 I/EdithP3 (18428): " + $marker + " sample=1")).Count) `
        ("captured as evidence: " + $marker)
}

# --- review I2: a batch the app is not processing must stop at row 3, not after 210 rows --------------------
# The documented failure (W4c, 2026-10-11): a run injected into a session the operator had already STOPPED, and wrote
# 10 rows with 849 window lines and ZERO feature lines each - an artifact that reads exactly like a product finding.
# Three consecutive windows with no criterion line of any kind cannot happen while the app is processing audio: every
# real injection in the artifacts produced at least `P3_ACOUSTIC label=Speech`. Rows already written are harness_error,
# so they are not settled and a re-run picks them up once the session is running.
Assert-Equal $true (Test-EdithBatchIsDead -RowWindows @(
        "P3_INJECT state=start`nP3_INJECT state=ambient_stopped`nP3_INJECT state=end",
        'P3_INJECT state=end chunks=148 accepted=148 drained=true',
        'P3_INJECT state=end chunks=120 accepted=120 drained=true')) 'three windows with no criterion line mean a dead batch'
Assert-Equal $false (Test-EdithBatchIsDead -RowWindows @(
        'P3_INJECT state=end',
        '10-12 01:57:18.239 I/EdithP3 (18428): P3_ACOUSTIC label=Speech',
        'P3_INJECT state=end')) 'one reacting window is enough to keep running'
Assert-Equal $false (Test-EdithBatchIsDead -RowWindows @(
        'P3_INJECT state=end',
        'P3_INJECT state=end')) 'two silent windows are not enough evidence to abort'

# --- review I1, second half: a TRIGGERING row is not settled when its `request` line appears ------------------
# Adding the chain markers to $NoiseMarkers was not enough. The poll loop breaks the moment a decision line appears and
# the capture is taken immediately, while the model call, the hint and its display ack arrive ~0.2-2 s AFTER `request`.
# Measured on the first chain-audit row: K-P-01 captured `P3_PROACTIVE request` and ZERO `P3_HTTP`/`P3_HINT` lines - so
# the artifact still could not show the chain it was extended for. A triggering row now gets a short settle wait before
# the capture; a refusing row (`skip reason=...`) must not pay it, and neither must an empty peek.
Assert-Equal 5 (Get-EdithChainSettleSeconds -PeekLines @(
        '10-12 06:39:00.000 I/System.out(13534): EdithSmart P3_PROACTIVE request ctx=5 chars=7')) 'a triggering peek waits for the chain tail'
Assert-Equal 0 (Get-EdithChainSettleSeconds -PeekLines @(
        '10-12 06:39:00.000 I/System.out(13534): EdithSmart P3_PROACTIVE skip reason=VAGUE')) 'a refusing peek does not wait'
Assert-Equal 0 (Get-EdithChainSettleSeconds -PeekLines @()) 'an empty peek does not wait'

if ($failures.Count -gt 0) {
    Write-Host ''
    $failures | ForEach-Object { Write-Host $_ }
    Write-Host ("FAILED {0} of {1} checks" -f $failures.Count, $checks)
    exit 1
}
Write-Host ("PASS {0} checks" -f $checks)
exit 0
