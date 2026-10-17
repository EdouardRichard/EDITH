# T-P2.1 - hint presentation capacity probe (P3.2 Stage 0; ceiling re-measured 2026-10-14).
#
# Pushes a hint of a KNOWN length through the real chain (phone arbiter -> CXR -> glasses TextView) as synthetic
# text (one CJK filler codepoint repeated N times) and reads the glasses' own log line
# `hint rendered lines=... maxLines=... chars=... clipped=... scrollMillis=...`, producing a chars -> lines table.
#
# NOTE ON ENCODING (2026-10-14): this file is deliberately **ASCII-only**. Windows PowerShell 5.1 reads a .ps1 without
# a BOM as ANSI; a UTF-8 Chinese comment then decodes to byte pairs that can swallow the following newline, and the
# script fails to parse with line numbers that do not match the file. Keep comments ASCII here.
#
# 2026-10-14 corrections and extensions:
#   1. The 2026-10-09 note "cannot measure above 120" had **two** independent 120s:
#      (a) the injector's own cap `DebugHintInjector.MAX_CHARS = 120` (now 400);
#      (b) **the glasses APK was a stale build** whose `HintWireCodec.MAX_TEXT_CODEPOINTS` was still 120, so every
#          SHOW with more than 120 codepoints arrived as `inbound undecodable` and the turn showed nothing at all -
#          exactly the symptom the owner reported as I3, and the phone-side fix **alone did not cure it**. After
#          installing the current glasses APK, 121 / 200 / 400 all render.
#      => **Both ends must run the same build when this link is measured.**
#   2. Default rungs are 120 / 200 / 400. **401 cannot be probed here**: the injector clamps to the wire bound (a test
#      pins them equal), so 401 is silently turned into 400. "Refused above the bound" is pinned by the JVM tests
#      `HintPolicyTest` / `HintWireCodecTest` instead.
#   3. The glasses line now carries `scrollMillis` (P3.2-CLIP scrolls an over-tall hint instead of clipping it).
#   4. **Wait for the phone to actually send the SHOW before reading the glasses.** The host queues by its reading
#      budget: while the previous hint is still inside its window the next one is not sent and the glasses show
#      nothing. The old version slept a fixed 2500 ms and therefore read the previous rung's stale line (or nothing),
#      which reads as "it rendered like that" when the truth was "it was never sent".
#
# Preconditions (checked here; the script reports a failure instead of pretending to succeed):
#   1. the phone debug APK is installed and contains `DebugHintInjector`;
#   2. the phone app has started a session, the current plugin is the hint probe (`p2-hint`) and the glasses display
#      is ready (the clock mapping is live) - otherwise the broadcast reports `accepted=false`, which this script
#      treats as an error rather than as "0 lines";
#   3. **the glasses and the phone run the same build** (see above); both serials must be given explicitly (omitting
#      the glasses serial would install the phone APK on the glasses).
#
# Privacy: only synthetic text is pushed, and the log lines carry counts and sizes only.
#
# Usage:
#   powershell -File tools/p3/glasses-hint-capacity.ps1 -PhoneSerial 2696813b -GlassesSerial 1901092544027662 `
#        -Chars 120,200,400 -OutFile docs/project/evidence/2026-10-14-hint-ceiling-table.md

param(
    [Parameter(Mandatory = $true)][string]$PhoneSerial,
    [Parameter(Mandatory = $true)][string]$GlassesSerial,
    [int[]]$Chars = @(120, 200, 400),
    [string]$Adb = "D:\Tools\Android\Sdk\platform-tools\adb.exe",
    [string]$OutFile = "",
    [int]$SettleMillis = 2500,
    # The host queues by reading budget; a rung's SHOW can stay unsent until the previous reading window ends.
    [int]$ShowTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    # NOT `-Args`: that name collides with PowerShell's automatic `$Args`, the parameter silently receives nothing,
    # and adb then prints its help text - which this script would otherwise read as a device response.
    param([string]$Serial, [string[]]$AdbArgs)
    $all = @("-s", $Serial) + $AdbArgs
    $out = & $Adb @all 2>&1
    return ($out | Out-String).Trim()
}

function Count-Shows {
    param([string]$Serial)
    return ((Invoke-Adb -Serial $Serial -AdbArgs @("logcat", "-d", "-s", "EdithHint")) -split "`n" |
        Where-Object { $_ -match "outbound: Show" }).Count
}

# --- preconditions --------------------------------------------------------------------------------
$devices = & $Adb devices | Out-String
if ($devices -notmatch [regex]::Escape($PhoneSerial)) { throw "phone $PhoneSerial not attached" }
if ($devices -notmatch [regex]::Escape($GlassesSerial)) { throw "glasses $GlassesSerial not attached" }

$glassesModel = Invoke-Adb -Serial $GlassesSerial -AdbArgs @("shell", "getprop", "ro.product.model")
if ($glassesModel -notmatch "glasses") {
    throw "serial $GlassesSerial does not look like the glasses (model=$glassesModel) - refusing to run"
}

# --- one row per rung -----------------------------------------------------------------------------
$rows = @()

foreach ($n in $Chars) {
    # Clear the glasses buffer so the line read below belongs to this rung.
    Invoke-Adb -Serial $GlassesSerial -AdbArgs @("logcat", "-c") | Out-Null

    $before = Count-Shows -Serial $PhoneSerial

    $broadcast = Invoke-Adb -Serial $PhoneSerial -AdbArgs @(
        "shell", "am", "broadcast", "-a", "edith.DEBUG_HINT",
        "-n", "edith.phone/.DebugHintInjector", "--ei", "chars", "$n")

    $sent = $false
    for ($waited = 0; $waited -lt $ShowTimeoutSeconds; $waited++) {
        Start-Sleep -Seconds 1
        if ((Count-Shows -Serial $PhoneSerial) -gt $before) { $sent = $true; break }
    }
    # Give the glasses time to receive it and finish a layout pass (`hint rendered` is logged after layout).
    Start-Sleep -Milliseconds $SettleMillis

    $injectLines = (Invoke-Adb -Serial $PhoneSerial -AdbArgs @("logcat", "-d", "-s", "EdithP3")) -split "`n" |
        Where-Object { $_ -match "P3_HINT_INJECT" }
    $phoneLine = $injectLines | Where-Object { $_ -match "chars=$n " } | Select-Object -Last 1
    if (-not $phoneLine) { $phoneLine = $injectLines | Select-Object -Last 1 }
    $accepted = if ($phoneLine -match "accepted=true") { $true } elseif ($phoneLine -match "accepted=false") { $false } else { $null }

    $glassesLines = (Invoke-Adb -Serial $GlassesSerial -AdbArgs @("logcat", "-d", "-s", "EdithGlasses")) -split "`n"
    $rendered = $glassesLines | Where-Object { $_ -match "hint rendered" } | Select-Object -Last 1

    $lines = $null; $maxLines = $null; $clipped = $null; $scrollMillis = $null
    if ($rendered -match "lines=(\d+)\s+maxLines=(\d+)\s+chars=(\d+)\s+clipped=(true|false)\s+scrollMillis=(\d+)") {
        $lines = [int]$Matches[1]; $maxLines = [int]$Matches[2]; $clipped = $Matches[4] -eq "true"; $scrollMillis = [int]$Matches[5]
    }

    $rows += [pscustomobject]@{
        chars    = $n
        accepted = $accepted
        sent     = $sent
        lines    = $lines
        maxLines = $maxLines
        clipped  = $clipped
        scrollMillis = $scrollMillis
        charsPerLine = if ($lines -and $lines -gt 0) { [math]::Round($n / $lines, 1) } else { $null }
        broadcast = ($broadcast -split "`n" | Select-Object -First 1)
    }
}

# --- output ---------------------------------------------------------------------------------------
$table = $rows | Format-Table -AutoSize | Out-String
Write-Output $table

if ($OutFile) {
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $md = @()
    $md += "| chars | accepted | sent | lines | maxLines | clipped | scrollMillis | chars/line |"
    $md += "|---|---|---|---|---|---|---|---|"
    foreach ($r in $rows) {
        $md += "| $($r.chars) | $($r.accepted) | $($r.sent) | $($r.lines) | $($r.maxLines) | $($r.clipped) | $($r.scrollMillis) | $($r.charsPerLine) |"
    }
    Set-Content -Path $OutFile -Value ($md -join "`n") -Encoding UTF8
    Write-Output "WROTE=$OutFile"
}
