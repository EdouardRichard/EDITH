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

    The `testsuite` start tag is read directly instead of parsing the whole document: one Gradle
    result file (`EngineBundleProvisioningTest`) embeds a Windows exception message whose raw
    control character makes the XML invalid, and a whole-document parse would silently drop that
    suite's 7 tests. The start tag is well formed even when the body is not.
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
    $line = Get-Content -LiteralPath $file.FullName -TotalCount 5 -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '<testsuite\b' } | Select-Object -First 1
    if (-not $line -or $line -notmatch '<testsuite\b([^>]*)>') {
        $malformed += $file.FullName
        continue
    }
    $attrs = $Matches[1]
    $suites++
    $tests += [int]([regex]::Match($attrs, 'tests="(\d+)"').Groups[1].Value)
    $failures += [int]([regex]::Match($attrs, 'failures="(\d+)"').Groups[1].Value)
    $errors += [int]([regex]::Match($attrs, 'errors="(\d+)"').Groups[1].Value)
    $skipped += [int]([regex]::Match($attrs, 'skipped="(\d+)"').Groups[1].Value)
}
"suites=$suites tests=$tests failures=$failures errors=$errors skipped=$skipped"
if ($malformed.Count -gt 0) {
    Write-Warning "unreadable suite tags: $($malformed.Count)"
    $malformed | ForEach-Object { Write-Warning "  $_" }
}
