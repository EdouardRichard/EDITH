#requires -Version 5.1
<#
.SYNOPSIS
    Aggregate EDITH JVM test counts from JUnit XML, excluding tools/probes.

.DESCRIPTION
    Reads every build/test-results/test*/TEST-*.xml below the repo root, skips any path containing
    \probes\ or \third_party\, and prints one line: suites, tests, failures, errors, skipped.
    Console summaries are never used (D103). `third_party/cxr` is a vendored sample with a stale
    2026-10-05 result set and is not a module of the root `settings.gradle.kts`; counting it would
    silently add 11/48 from an old run.

    Each document is read with `File.ReadAllText(..., UTF8)` and parsed as a whole. **Do not use
    `Get-Content` here**: under Windows PowerShell 5.1 it decodes UTF-8 as ANSI, which makes a
    perfectly valid Gradle XML file (one whose skip message contains a non-ASCII path) fail `[xml]`
    with a misleading "unexpected token". That was a P6 review finding; the explicit UTF-8 read
    parses all project-only files.
#>
param([string] $Root = (Get-Location).Path)

$xml = Get-ChildItem -LiteralPath $Root -Recurse -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
    Where-Object {
        $_.FullName -match '\\build\\test-results\\test[^\\]*\\' -and
        $_.FullName -notmatch '\\probes\\' -and
        $_.FullName -notmatch '\\third_party\\'
    }

$suites = 0; $tests = 0; $failures = 0; $errors = 0; $skipped = 0; $malformed = @()
foreach ($file in $xml) {
    $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    try {
        [xml] $doc = $text
    } catch {
        $malformed += $file.FullName
        continue
    }
    $suite = $doc.testsuite
    if ($null -eq $suite) { $malformed += $file.FullName; continue }
    $suites++
    $tests += [int] $suite.tests
    $failures += [int] $suite.failures
    $errors += [int] $suite.errors
    $skipped += [int] $suite.skipped
}
"suites=$suites tests=$tests failures=$failures errors=$errors skipped=$skipped"
if ($malformed.Count -gt 0) {
    Write-Warning "unreadable XML files: $($malformed.Count)"
    $malformed | ForEach-Object { Write-Warning "  $_" }
}
