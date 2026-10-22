$ErrorActionPreference = 'Stop'

# P3.0 T3a harness self-test. Dot-sourcing `shell-uid-matrix.ps1` is inert: the matrix runs only under `-Run`.
#
# The functions under test decide what the matrix says. Two mistakes matter most:
#
#   1. Trusting the exit code. `adb shell input tap` can exit 0 while printing `Error: Injecting event failed` - the
#      action did not happen. A matrix built on exit codes alone would report OK for actions that never ran, and the
#      P3 risk table would then be built on fiction.
#   2. Reporting a partial matrix as complete. A device that was absent, or a backend whose authorisation had lapsed
#      since the last run, must appear as UNTESTED/BLOCKED rather than simply being missing from the report.
#
# ROOT IS NOT TESTED. User decision (2026-10-08): Root exists in the design as a possibility and does not need
# verifying, so this harness never probes it and the self-test has no root cases.
#
# This file is deliberately pure ASCII; see the note in shell-uid-matrix.ps1 for why.
. (Join-Path $PSScriptRoot 'shell-uid-matrix.ps1')

$script:passed = 0
$script:failed = 0

function Check {
    param([string]$Name, [scriptblock]$Assert)
    try {
        & $Assert
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        Write-Output "FAIL $Name : $($_.Exception.Message)"
    }
}

Check 'verdict-accepts-a-clean-zero-exit' {
    if ((Get-ActionVerdict -ExitCode 0 -Stdout 'PixelFormat') -ne 'OK') { throw 'a clean zero exit must be OK' }
}

Check 'verdict-rejects-an-error-that-still-exited-zero' {
    $verdict = Get-ActionVerdict -ExitCode 0 -Stdout 'Error: Injecting event failed'
    if ($verdict -ne 'BLOCKED') { throw "exit 0 with an injection error must be BLOCKED, got $verdict" }
}

Check 'verdict-rejects-permission-denial-that-still-exited-zero' {
    $verdict = Get-ActionVerdict -ExitCode 0 -Stdout 'java.lang.SecurityException: Permission denial: writing to settings requires'
    if ($verdict -ne 'BLOCKED') { throw "a permission denial must be BLOCKED, got $verdict" }
}

Check 'verdict-rejects-a-nonzero-exit' {
    if ((Get-ActionVerdict -ExitCode 1 -Stdout '') -ne 'BLOCKED') { throw 'a non-zero exit must be BLOCKED' }
}

Check 'verdict-rejects-operation-not-permitted' {
    $verdict = Get-ActionVerdict -ExitCode 0 -Stdout 'cat: /data/data/com.android.settings/shared_prefs: Operation not permitted'
    if ($verdict -ne 'BLOCKED') { throw "an unreadable private path must be BLOCKED, got $verdict" }
}

Check 'coverage-passes-when-the-expected-pair-is-present' {
    $rows = @(@{ device = 'K40'; action = 'input tap'; backend = 'shell'; exitCode = 0; stdout = ''; verdict = 'OK' })
    if (-not (Assert-EveryActionRecorded -Rows $rows -ExpectedActions @('input tap') -ExpectedBackends @('shell'))) {
        throw 'a present action/backend pair must satisfy coverage'
    }
}

Check 'coverage-fails-when-an-action-is-missing' {
    $rows = @(@{ device = 'K40'; action = 'input tap'; backend = 'shell'; exitCode = 0; stdout = ''; verdict = 'OK' })
    if (Assert-EveryActionRecorded -Rows $rows -ExpectedActions @('input tap', 'am start -n') -ExpectedBackends @('shell')) {
        throw 'a missing action must fail coverage'
    }
}

Check 'coverage-fails-when-a-device-column-is-missing' {
    $rows = @(@{ device = 'K40'; action = 'input tap'; backend = 'shell'; exitCode = 0; stdout = ''; verdict = 'OK' })
    if (Assert-EveryActionRecorded -Rows $rows -ExpectedActions @('input tap') -ExpectedBackends @('shell') -ExpectedDevices @('K40', 'K70')) {
        throw 'an absent device must be reported, not silently dropped from the matrix'
    }
}

Check 'coverage-fails-on-empty-input' {
    if (Assert-EveryActionRecorded -Rows @() -ExpectedActions @('input tap') -ExpectedBackends @('shell')) {
        throw 'an empty matrix is not coverage'
    }
}

Check 'shell-backend-requires-the-shell-uid' {
    $verdict = Get-ShellBackendVerdict -ExitCode 0 -Stdout 'uid=2000(shell) gid=2000(shell)'
    if ($verdict -ne 'OK') { throw "shell uid must be OK, got $verdict" }
    # A privileged shell answering on the shell backend's behalf would overstate what an unprivileged path can do.
    $verdict = Get-ShellBackendVerdict -ExitCode 0 -Stdout 'uid=0(root) gid=0(root)'
    if ($verdict -ne 'BLOCKED') { throw "root answering for the shell backend must be BLOCKED, got $verdict" }
    $verdict = Get-ShellBackendVerdict -ExitCode 1 -Stdout 'su: inaccessible or not found'
    if ($verdict -ne 'BLOCKED') { throw "a failed backend probe must be BLOCKED, got $verdict" }
}

Check 'every-ui-injecting-action-is-marked-for-restore' {
    # The incident this pins down: the first real run left the operator's phone in the Settings search screen with
    # "edith" typed into it. Any action that can visibly change the device must declare that it restores afterwards.
    foreach ($action in $script:ActionMatrix) {
        if ($action.cmd -match '^input ' -or $action.cmd -match '^am start ') {
            if (-not $action.restores) {
                throw "action '$($action.name)' injects UI or starts an activity but is not marked restores"
            }
        }
    }
}

Check 'weak-actions-are-inconclusive-rather-than-a-capability-claim' {
    # The device run produced this exact pair: `pm grant` exited 0 while changing nothing (the permission was already
    # system-fixed and granted), so exit 0 must not become an OK row.
    if ((Get-RowVerdict -Weak $true -ExitCode 0 -Stdout '') -ne 'INCONCLUSIVE') {
        throw 'a weak action must be INCONCLUSIVE even on a clean zero exit'
    }
    if ((Get-RowVerdict -Weak $false -ExitCode 0 -Stdout 'PixelFormat') -ne 'OK') {
        throw 'a strong action with a clean zero exit must still be OK'
    }
    if ((Get-RowVerdict -Weak $false -ExitCode 0 -Stdout 'Error: Injecting event failed') -ne 'BLOCKED') {
        throw 'a strong action with an error on stdout must still be BLOCKED'
    }
}

Check 'disruptive-actions-stay-in-the-matrix-so-they-can-be-reported-untested' {
    $disruptive = $script:ActionMatrix | Where-Object { $_.disruptive }
    if ($disruptive.Count -eq 0) { throw 'the disruptive action must stay in the matrix so it can be reported as UNTESTED' }
}

Write-Output "SHELL_UID_MATRIX_TESTS passed=$script:passed failed=$script:failed"
if ($script:failed -gt 0) { exit 1 }
