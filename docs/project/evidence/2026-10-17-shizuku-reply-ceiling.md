# The Shizuku `IRemoteShell` reply ceiling, measured: it is **characters**, not bytes

- Date: 2026-10-17 (device clock 00:43:59 – 00:58:03 for the runs below)
- Device: Redmi K40 (`alioth`, serial `2696813b`), MIUI `V816.0.10.0.TKHCNXM`, Android 13 / API 33, `user` build, page size 4096
- Repo: `D:\Project_new\EDITH`, branch `feat/0.21-agentloop-context-redesign`, HEAD `285efad`
- Instrument: the existing debug probe `edith.phone/.DebugShellCapProbe` (action `edith.DEBUG_SHELLCAP`) on the **production** `ShizukuUserServiceShell`; **no production code, no test and no new probe was written or modified** by this round
- Privacy: this file contains only counts, byte sizes, flags, codes, class names and the fixed command runbook. It contains **no** command output, reply body, package list or credential. The probe never logs a command or a reply body, and the label is sanitised to `[A-Za-z0-9_-]`.
- Nothing was committed: the file is left in the working tree (see §11).

---

## 1. The answer (Experiment 3)

**The ceiling is governed by the character count (UTF-16 units) of the JSON-encoded reply, not by the UTF-8 byte count of the payload.**

Two decisive runs, **same requested output size in bytes (400 000 B), same command shape**, only the encoding differs:

| arm | command | requested | `exit` | `stdoutBytes` | how many characters that is | bytes actually written to the Binder (libbinder's own log) | result |
|---|---|---|---|---|---|---|---|
| ASCII | `yes a \| head -c 400000` | 400 000 B | -1 | 0 | 600 034 JSON chars | **1 200 080 B** | **FAIL** `SHELL_REPLY_TOO_LARGE` / `carrier` / `DeadObjectException` |
| CJK | `yes 中 \| head -c 400000` | 400 000 B | 0 | 400 000 | 300 034 JSON chars | **600 080 B** | **PASS** (`code=none`) |

Both arms produced exactly 400 000 output bytes. The ASCII arm needed **twice** the wire bytes of the CJK arm (1 200 080 vs 600 080), because ASCII costs 1 UTF-16 unit per byte while CJK costs ~1 unit per 3 bytes; the ASCII attempt exceeded the Binder transaction budget and the CJK one did not.

The same inversion holds at a matched command shape with no newlines at all (`yes YWFh | base64 -d` vs `yes 5Lit | base64 -d`, i.e. 1-byte vs 3-byte UTF-8 characters), at **the same requested 540 000 B**:

| arm | requested | `exit` | `stdoutBytes` | characters | wire bytes | result |
|---|---|---|---|---|---|---|
| ASCII | 540 000 B | -1 | 0 | 540 034 JSON chars | 1 080 080 B | **FAIL** |
| CJK | 540 000 B | 0 | 540 000 | 180 034 JSON chars | 360 080 B | **PASS** |

And when the four arms are pushed to their own ceilings, **all four stop at the same character count (~520 000 JSON characters) while stopping at wildly different byte counts** (§5, tables 5.3/5.4): escape-free ASCII passes at 520 000 B and fails at 521 000 B, newline-terminated CJK passes at 692 000 B and fails at 694 000 B, and unescaped CJK passes at 1 557 999 B and fails at 1 562 001 B. A bytes-based rule cannot produce that; a characters-based rule predicts it exactly.

So: **a future tool's safe reply size must be budgeted in JSON-encoded characters (UTF-16 units), not in UTF-8 bytes.** Under the same rule, a CJK reply reaches *further* in bytes than an ASCII one — by 3× for unescaped text — and the Shizuku state probes that read Chinese-containing output are on the cheaper side of the rule.

---

## 2. What the wire actually counts (the model, confirmed exactly)

The reply that crosses the Binder is the JSON string built by `ShellReply.encode`: `{"exit":0,"stdout":"…","stderr":""}` — a 34-character wrapper plus the payload with JSON escaping (`\n` = 2 chars, a control byte such as NUL = 6 chars as `\u0000`, a CJK character = 1 char).

`Parcel.writeString` writes a 4-byte length, then the string as 2 bytes per UTF-16 unit plus a terminator. libbinder itself logs the reply transaction's size (`W edith.phone:shell: Large reply transaction of <N> bytes, interface descriptor edith.phone.actions.IRemoteShell, code 2`; the string is present in `/system/lib64/libbinder.so`, verified on-device with `grep -a -c`). That gives an independent, measured byte count per attempt.

Over **every one of the 22 distinct reply sizes observed, across 4 encodings and 4 reply shapes**, the measured size matched

```
wire = align4(2 × JSON_chars + 10)
```

**exactly, in every case** (`JSON_chars` computed from the known command shape; `+10` = 4-byte length + 2-byte terminator + 4 bytes of Parcel framing; `align4` accounts for alignment padding). Examples: 325 000 B of `yes a` ⇒ 487 534 chars ⇒ 975 078 ⇒ **975 080 measured**; 400 000 B of `yes 中` ⇒ 300 034 chars ⇒ **600 080 measured**; 1 557 999 B of pure `中` ⇒ 519 367 chars ⇒ **1 038 744 measured**.

Two consequences:
- the cost of a reply is a **character** cost (with escapes costing their escaped length), and every measured pass/fail decision below is explained by it;
- the *underlying* limit is the receiving process's Binder transaction buffer: `BINDER_VM_SIZE = 1 MiB − 2 pages` ([`ProcessState.cpp` line 47, android-13.0.0_r1](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-13.0.0_r1/libs/binder/ProcessState.cpp)) = 1 040 384 B on this 4 KiB-page device. Half of that, minus framing, is where the ~520 000-character ceiling comes from.

**Measured straddle of that buffer** (all four arms pooled, wire bytes): **pass at 1 040 080 B, fail at 1 040 700 B** — the interval contains the documented 1 040 384 B constant. In character terms: **pass at 520 034 JSON chars, fail at 520 345 JSON chars** (a 311-character window).

---

## 3. Experiment 1 — is the ASCII ceiling deterministic?

**Deterministic at the sizes tested: no run ever landed on both sides.** 10 identical runs just below the boundary all passed and 10 just above all failed, and the failing runs all failed the same way.

| size (same command `yes a \| head -c N`) | runs | pass | fail |
|---|---|---|---|
| 325 000 B | 10 | **10** | 0 |
| 350 000 B | 10 | 0 | **10** |
| 346 482 B (the boundary found in Exp 2) | 10 | **10** | 0 |
| 346 874 B (the boundary found in Exp 2) | 10 | 0 | **10** |

Every failure in this session was identical: `exit=-1 stdoutBytes=0 stderrBytes=21 truncated=false code=SHELL_REPLY_TOO_LARGE arm=carrier throwable=android.os.DeadObjectException`. Every failure was followed by a live channel again on the next run (`ready=true`, and the next call got an answer), so the failure is per-attempt and recoverable, as R-9 recorded.

**Caveat that matters for the product:** "deterministic" here means *within a session*. Across sessions the ceiling has moved (250–300 kB on 2026-10-16, 325–350 kB on 2026-10-17, 346 482/346 874 B now) even though the command and the build are the same. The buffer is **per process and shared by every in-flight transaction of the app process**, so the headroom at the instant of the reply depends on what else edith.phone is doing. That is a hypothesis I could not measure (no root/debugfs to read the app's buffer occupancy) — see §9, item 4.

---

## 4. Experiment 2 — the tightest ASCII interval established

Binary search between the two known endpoints (325 000 B pass, 350 000 B fail), one adaptive probe per step, all on `yes a | head -c N`:

| probe (requested B) | result | wire B (libbinder) | JSON chars |
|---|---|---|---|
| 337 500 | pass | 1 012 580 | 506 284 |
| 343 750 | pass | 1 031 328 | 515 659 |
| 346 874 | **fail** (confirmed by an immediate re-run, also fail) | 1 040 700 | 520 345 |
| 345 312 | pass | 1 036 016 | 518 002 |
| 346 092 | pass | 1 038 356 | 519 172 |
| **346 482** | **pass** | 1 039 524 | 519 757 |
| **346 874** | **fail** | 1 040 700 | 520 345 |

**Interval: passes at ≤ 346 482 B, fails at ≥ 346 874 B — a 392-byte window**, and both endpoints were then repeated 10× each with the same 10/0 and 0/10 split (§3). In wire terms the window is 1 039 524 B (pass) vs 1 040 700 B (fail).

The same bracket seen from the other three arms (one run per point, see §5.4) narrows the *character* boundary to **[520 034 pass, 520 345 fail)**.

---

## 5. Experiment 3 — encoding hypothesis, full numbers

### 5.1 The task's requested pair (newlines intact, as `yes` produces them)

| run | command | requested B | `exit` | `stdoutBytes` | `stderrBytes` | `truncated` | `code` | elapsedMs | wire B | payload chars | JSON chars |
|---|---|---|---|---|---|---|---|---|---|---|---|
| nA400-1 | `yes a \| head -c 400000` | 400 000 | -1 | 0 | 21 | false | `SHELL_REPLY_TOO_LARGE` | 199 | 1 200 080 | 400 000 | 600 034 |
| nC400-1 | `yes 中 \| head -c 400000` | 400 000 | 0 | 400 000 | 0 | false | `none` | 106 | 600 080 | 200 000 | 300 034 |
| nA400-2 | `yes a \| head -c 400000` | 400 000 | -1 | 0 | 21 | false | `SHELL_REPLY_TOO_LARGE` | 185 | 1 200 080 | 400 000 | 600 034 |
| nC400-2 | `yes 中 \| head -c 400000` | 400 000 | 0 | 400 000 | 0 | false | `none` | 148 | 600 080 | 200 000 | 300 034 |

Note the payload character counts: `yes a` emits `a\n` (2 UTF-8 bytes, 2 chars, and the newline escapes to 2 chars ⇒ 3 wire-chars per 2 payload bytes); `yes 中` emits `中\n` (4 UTF-8 bytes, 2 chars, newline escapes to 2 ⇒ 3 wire-chars per 4 payload bytes). **The 400 000-byte CJK payload is half the character count of the 400 000-byte ASCII payload, and that is exactly why one fits and the other does not.** Truncation of a 3-byte character at the `head -c` boundary is possible in principle; at 400 000 (divisible by 4, so the `中\n` stream ends exactly on a line boundary) the delivered byte count was exact, and in no passing CJK run did the byte count differ from the request.

### 5.2 Matched-shape arm with no newlines (the escaping confound removed)

Same pipeline shape for both arms, only the base64 literal differs, so ASCII and CJK differ *only* in UTF-8 byte width:

| run | command | requested B | `exit` | `stdoutBytes` | `code` | wire B | payload chars | JSON chars |
|---|---|---|---|---|---|---|---|---|
| nnA540b-1 | `yes YWFh \| base64 -d \| head -c 540000` | 540 000 | -1 | 0 | `SHELL_REPLY_TOO_LARGE` | 1 080 080 | 540 000 | 540 034 |
| nnA540b-2 | same | 540 000 | -1 | 0 | `SHELL_REPLY_TOO_LARGE` | 1 080 080 | 540 000 | 540 034 |
| nnC540-1 | `yes 5Lit \| base64 -d \| head -c 540000` | 540 000 | 0 | 540 000 | `none` | 360 080 | 180 000 | 180 034 |
| nnC540-2 | same | 540 000 | 0 | 540 000 | `none` | 360 080 | 180 000 | 180 034 |

### 5.3 Ceilings per arm (byte ceilings differ by up to 4.5×; character ceilings agree)

| arm | command shape | largest pass (requested B, runs) | smallest fail (requested B, runs) | pass wire B | fail wire B | pass JSON chars | fail JSON chars |
|---|---|---|---|---|---|---|---|
| ASCII, newline-terminated | `yes a \| head -c N` | **346 482** (10/10) | **346 874** (10/10) | 1 039 524 | 1 040 700 | 519 757 | 520 345 |
| ASCII, no newline | `yes YWFh \| base64 -d \| head -c N` | 520 000 (1/1) | 521 000 (1/1) | 1 040 080 | 1 042 080 | 520 034 | 521 034 |
| CJK, newline-terminated | `yes 中 \| head -c N` | 692 000 (1/1) | 694 000 (1/1) | 1 038 080 | 1 041 080 | 519 034 | 520 534 |
| CJK, no newline | `yes 5Lit \| base64 -d \| head -c N` | **1 557 999** (1/1) | 1 562 001 (1/1) | 1 038 744 | 1 041 412 | 519 367 | 520 701 |

The byte ceilings range over 346 482 → 1 557 999 (4.5×). Every arm's **pass** point is ≥ 519 034 JSON chars and every arm's **fail** point is ≤ 521 034, and pooling every arm the boundary is bracketed by **520 034 (pass) / 520 345 (fail)**.

### 5.4 The single tightest observation

`nnA520b` (`yes YWFh | base64 -d | head -c 520000`) **passed** at wire 1 040 080 B, and `F346874` (`yes a | head -c 346874`) **failed** at wire 1 040 700 B. Both are ~520 000 JSON characters; the pass/fail line in wire bytes lies within **(1 040 080, 1 040 700]**, which contains the documented 1 040 384 B `BINDER_VM_SIZE`.

---

## 6. Experiment 4 — do the app's three real state probes have margin?

Run through the same probe, on the same channel, in the same session (labels `e4-*`):

| command (as the app issues it) | `exit` | `stdoutBytes` | `stderrBytes` | `truncated` | `code` | elapsedMs | cost in the unit that matters (JSON chars / wire B) | headroom vs the ~520 000-char / ~1 040 000 B ceiling |
|---|---|---|---|---|---|---|---|
| `pm list packages edith.phone` | 0 | 50 | **0** | false | `none` | 57 | ~84 chars / ~178 B | ~5 800× |
| `dumpsys package edith.phone` | 0 | 11 588 | **0** | false | `none` | 43 | ~11 622 chars / ~23 254 B | **~45×** |
| `settings get system screen_brightness` | 0 | 3 | **0** | false | `none` | 60 | ~37 chars / ~84 B | ~12 400× |

(Char/wire figures are derived from the measured `stdoutBytes` with the `align4(2 × JSON chars + 10)` rule of §2, so they are the same model every other arm in this file was checked against; they are marked derived because these three replies were below libbinder's warning threshold and so produced no wire log line of their own.)

**This settles a claim that was previously JVM-only.** `PrivilegedShell.kt` (`isAStateReply`, lines ~110–131) says the empty-`stderr` half of the whitelist "is pinned on the JVM … **not yet device-measured**: the probe never ran those three". All three shape s now have a device reading and **`stderrBytes=0` in every one**, with `failure = null` (so `isAStateReply()` is true for each) and `truncated=false`. The claim is **confirmed, not refuted**, on the K40 for the three exact commands the build issues.

Margin: the largest of the three, `dumpsys package edith.phone`, is 11 588 payload bytes ≈ **11 622 JSON characters ≈ 23 254 wire bytes** — about **2.2 % of the ceiling**, i.e. a **~45× margin** (and the comparison is only honest in those units: comparing its 11 588 payload bytes against a ~1 040 000 *wire-byte* ceiling would overstate the margin by ~2×). Nothing the app issues today is anywhere near the limit.

---

## 7. Every run

`wire B` = the size libbinder's own `Large reply transaction` warning reported for that attempt (measured, not derived); `—` = the attempt was below libbinder's warning threshold and logged nothing. All rows were taken with `ready=true` at the `state=CHANNEL` line immediately before the call, except `dA346874` (its `CHANNEL` line fell outside the read window; the call still answered with a failure code). Every `code=SHELL_REPLY_TOO_LARGE` row also carried `arm=carrier throwable=android.os.DeadObjectException` — that never varied, so it is stated once here instead of in every row.

### 7.1 Control (channel + probe liveness)

| label | requested B | exit | stdoutBytes | stderrBytes | truncated | code | elapsedMs | wire B |
|---|---|---|---|---|---|---|---|---|
| control-0 (`settings get system screen_brightness`) | 3 | 0 | 3 | 0 | false | none | 54 | — |

### 7.2 Experiment 1 (determinism; all `yes a | head -c N`)

| label | requested B | exit | stdoutBytes | stderrBytes | truncated | code | elapsedMs | wire B |
|---|---|---|---|---|---|---|---|---|
| a325-r1 | 325 000 | 0 | 325 000 | 0 | false | none | 168 | 975 080 |
| a325-r2 | 325 000 | 0 | 325 000 | 0 | false | none | 177 | 975 080 |
| a325-r3 | 325 000 | 0 | 325 000 | 0 | false | none | 165 | 975 080 |
| a325-r4 | 325 000 | 0 | 325 000 | 0 | false | none | 162 | 975 080 |
| a325-r5 | 325 000 | 0 | 325 000 | 0 | false | none | 174 | 975 080 |
| a325-r6 | 325 000 | 0 | 325 000 | 0 | false | none | 197 | 975 080 |
| a325-r7 | 325 000 | 0 | 325 000 | 0 | false | none | 167 | 975 080 |
| a325-r8 | 325 000 | 0 | 325 000 | 0 | false | none | 165 | 975 080 |
| a325-r9 | 325 000 | 0 | 325 000 | 0 | false | none | 169 | 975 080 |
| a325-r10 | 325 000 | 0 | 325 000 | 0 | false | none | 153 | 975 080 |
| a350-r1 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 165 | 1 050 080 |
| a350-r2 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 161 | 1 050 080 |
| a350-r3 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 162 | 1 050 080 |
| a350-r4 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 215 | 1 050 080 |
| a350-r5 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 144 | 1 050 080 |
| a350-r6 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 150 | 1 050 080 |
| a350-r7 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 148 | 1 050 080 |
| a350-r8 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 167 | 1 050 080 |
| a350-r9 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 157 | 1 050 080 |
| a350-r10 | 350 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 155 | 1 050 080 |

### 7.3 Experiment 2 (binary search and the two endpoints, 10× each)

The first seven search calls (`bs1-337500` … `bs6-346482`) were made and their decisions and wire sizes recorded, but their per-run probe lines were swallowed by a driver bug (§9, item 5); the four sizes that were not re-run elsewhere are re-run here with full rows, and they reproduce the same decisions and the same wire sizes.

| label | requested B | exit | stdoutBytes | stderrBytes | truncated | code | elapsedMs | wire B |
|---|---|---|---|---|---|---|---|---|
| bs1-337500 *(row lost, decision PASS)* | 337 500 | — | — | — | — | none | — | 1 012 580 |
| bs2-343750 *(row lost, decision PASS)* | 343 750 | — | — | — | — | none | — | 1 031 328 |
| bs3-346874 *(row lost, decision FAIL)* | 346 874 | — | — | — | — | SHELL_REPLY_TOO_LARGE | — | 1 040 700 |
| bs3b-346874 *(immediate re-run, FAIL)* | 346 874 | — | — | — | — | SHELL_REPLY_TOO_LARGE | — | 1 040 700 |
| bs4-345312 *(row lost, decision PASS)* | 345 312 | — | — | — | — | none | — | 1 036 016 |
| bs5-346092 *(row lost, decision PASS)* | 346 092 | — | — | — | — | none | — | 1 038 356 |
| bs6-346482 *(row lost, decision PASS)* | 346 482 | — | — | — | — | none | — | 1 039 524 |
| bs-337500 | 337 500 | 0 | 337 500 | 0 | false | none | 161 | 1 012 580 |
| bs-343750 | 343 750 | 0 | 343 750 | 0 | false | none | 170 | 1 031 328 |
| bs-345312 | 345 312 | 0 | 345 312 | 0 | false | none | 168 | 1 036 016 |
| bs-346092 | 346 092 | 0 | 346 092 | 0 | false | none | 164 | 1 038 356 |
| P346482-r1 | 346 482 | 0 | 346 482 | 0 | false | none | 206 | 1 039 524 |
| P346482-r2 | 346 482 | 0 | 346 482 | 0 | false | none | 204 | 1 039 524 |
| P346482-r3 | 346 482 | 0 | 346 482 | 0 | false | none | 157 | 1 039 524 |
| P346482-r4 | 346 482 | 0 | 346 482 | 0 | false | none | 166 | 1 039 524 |
| P346482-r5 | 346 482 | 0 | 346 482 | 0 | false | none | 170 | 1 039 524 |
| P346482-r6 | 346 482 | 0 | 346 482 | 0 | false | none | 205 | 1 039 524 |
| P346482-r7 | 346 482 | 0 | 346 482 | 0 | false | none | 180 | 1 039 524 |
| P346482-r8 | 346 482 | 0 | 346 482 | 0 | false | none | 171 | 1 039 524 |
| P346482-r9 | 346 482 | 0 | 346 482 | 0 | false | none | 171 | 1 039 524 |
| P346482-r10 | 346 482 | 0 | 346 482 | 0 | false | none | 195 | 1 039 524 |
| F346874-r1 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 156 | 1 040 700 |
| F346874-r2 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 140 | 1 040 700 |
| F346874-r3 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 147 | 1 040 700 |
| F346874-r4 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 190 | 1 040 700 |
| F346874-r5 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 179 | 1 040 700 |
| F346874-r6 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 156 | 1 040 700 |
| F346874-r7 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 146 | 1 040 700 |
| F346874-r8 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 144 | 1 040 700 |
| F346874-r9 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 144 | 1 040 700 |
| F346874-r10 | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 144 | 1 040 700 |

### 7.4 Experiment 3

| label | arm / command | requested B | exit | stdoutBytes | stderrBytes | truncated | code | elapsedMs | wire B |
|---|---|---|---|---|---|---|---|---|---|
| nA400-1 | ASCII-LF `yes a` | 400 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 199 | 1 200 080 |
| nC400-1 | CJK-LF `yes 中` | 400 000 | 0 | 400 000 | 0 | false | none | 106 | 600 080 |
| nA400-2 | ASCII-LF | 400 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 185 | 1 200 080 |
| nC400-2 | CJK-LF | 400 000 | 0 | 400 000 | 0 | false | none | 148 | 600 080 |
| bA540-1 | **invalid** (LF broke the command at the shell, §9.1) | 540 000 | 1 | 0 | 39 | false | none | 34 | — |
| bC540-1 | **invalid** (same) | 540 000 | 1 | 0 | 39 | false | none | 28 | — |
| bA540-2 | **invalid** (same) | 540 000 | 1 | 0 | 39 | false | none | 37 | — |
| bC540-2 | **invalid** (same) | 540 000 | 1 | 0 | 39 | false | none | 29 | — |
| cA519 | **invalid** (same) | 519 000 | 1 | 0 | 39 | false | none | 38 | — |
| cA520 | **invalid** (same) | 520 000 | 1 | 0 | 39 | false | none | 32 | — |
| cA521 | **invalid** (same) | 521 000 | 1 | 0 | 39 | false | none | 41 | — |
| cC692 | CJK-LF | 692 000 | 0 | 692 000 | 0 | false | none | 175 | 1 038 080 |
| cC694 | CJK-LF | 694 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 211 | 1 041 080 |
| cCnn1557999 | **invalid** (same) | 1 557 999 | 1 | 0 | 39 | false | none | 30 | — |
| cCnn1562001 | **invalid** (same) | 1 562 001 | 1 | 0 | 39 | false | none | 25 | — |
| dA346482 | ASCII-LF (boundary re-check) | 346 482 | 0 | 346 482 | 0 | false | none | 184 | 1 039 524 |
| dA346874 | ASCII-LF (boundary re-check) | 346 874 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 157 | 1 040 700 |
| nnA540-1 | **invalid** (quote-stripped ⇒ NUL payload, §9.1) | 540 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 99 | 6 480 080 |
| nnC540-1 | CJK-NONL `yes 5Lit \| base64 -d` | 540 000 | 0 | 540 000 | 0 | false | none | 304 | 360 080 |
| nnA540-2 | **invalid** (NUL payload) | 540 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 92 | 6 480 080 |
| nnC540-2 | CJK-NONL | 540 000 | 0 | 540 000 | 0 | false | none | 263 | 360 080 |
| nnA519 | **invalid** (NUL payload) | 519 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 90 | 6 228 080 |
| nnA520 | **invalid** (NUL payload) | 520 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 103 | 6 240 080 |
| nnA521 | **invalid** (NUL payload) | 521 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 103 | 6 252 080 |
| nnC1557999 | CJK-NONL | 1 557 999 | 0 | 1 557 999 | 0 | false | none | 742 | 1 038 744 |
| nnC1562001 | CJK-NONL | 1 562 001 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 748 | 1 041 412 |
| nnA519b | ASCII-NONL `yes YWFh \| base64 -d` | 519 000 | 0 | 519 000 | 0 | false | none | 299 | 1 038 080 |
| nnA520b | ASCII-NONL | 520 000 | 0 | 520 000 | 0 | false | none | 272 | 1 040 080 |
| nnA521b | ASCII-NONL | 521 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 264 | 1 042 080 |
| nnA540b-1 | ASCII-NONL | 540 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 282 | 1 080 080 |
| nnA540b-2 | ASCII-NONL | 540 000 | -1 | 0 | 21 | false | SHELL_REPLY_TOO_LARGE | 364 | 1 080 080 |

### 7.5 Experiment 4

| label | command | exit | stdoutBytes | stderrBytes | truncated | code | elapsedMs | wire B |
|---|---|---|---|---|---|---|---|---|
| e4-pm-list | `pm list packages edith.phone` | 0 | 50 | 0 | false | none | 57 | — |
| e4-dumpsys | `dumpsys package edith.phone` | 0 | 11 588 | 0 | false | none | 43 | — |
| e4-settings | `settings get system screen_brightness` | 0 | 3 | 0 | false | none | 60 | — |

---

## 8. Exact commands used

```powershell
# --- the probe (unchanged, existing debug-only receiver) ---
adb -s 2696813b shell "am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe --es label <label> --es command '<command>'"

# --- read back ---
adb -s 2696813b logcat -d -s EdithP3:* -t 300            # the probe's own line, matched by unique label
adb -s 2696813b logcat -d | Select-String "transaction of" # libbinder's own reply-transaction size

# --- control arm (proves probe + channel live) ---
#   command: settings get system screen_brightness

# --- Experiment 1 / 2 (ASCII, newline-terminated; N = 325000, 337500, 343750, 345312, 346092, 346482, 346874, 350000) ---
#   command: yes a | head -c <N>

# --- Experiment 3, arm A (ASCII, newline-terminated) ---
#   command: yes a | head -c 400000

# --- Experiment 3, arm B (CJK, newline-terminated) ---
#   command: yes 中 | head -c 400000        # also 692000 / 694000
# NB: `yes` emits "<char>\n", so half the delivered bytes are newlines, which JSON-escape to 2 chars each.
#     Truncation may split a multi-byte character; at the sizes used here it did not (byte counts were exact).

# --- Experiment 3, arm C (ASCII, no newline; quote-free so the extra survives the shell) ---
#   command: yes YWFh | base64 -d | head -c <N>     # "YWFh" decodes to "aaa"

# --- Experiment 3, arm D (CJK, no newline; same shape, only the literal differs) ---
#   command: yes 5Lit | base64 -d | head -c <N>     # "5Lit" decodes to the 3 UTF-8 bytes e4 b8 ad (中)

# --- byte-shape verification of the generators (before use) ---
adb -s 2696813b shell "head -c 9 /dev/zero | tr '\0' 'a' | od -An -tx1"
adb -s 2696813b shell "yes 5Lit | base64 -d | head -c 9 | od -An -tx1"
adb -s 2696813b shell "yes 5Lit | base64 -d | head -c 1562001 | wc -c"

# --- Experiment 4 ---
#   commands: pm list packages edith.phone ; dumpsys package edith.phone ; settings get system screen_brightness

# --- device facts ---
adb -s 2696813b shell getconf PAGESIZE                     # 4096
adb -s 2696813b shell getprop ro.build.fingerprint         # Redmi/alioth/alioth:13/TKQ1.221114.001/V816.0.10.0.TKHCNXM:user/release-keys
adb -s 2696813b shell "grep -a -c 'Large reply transaction' /system/lib64/libbinder.so"   # 1 ⇒ the log line is libbinder's
```

No Shizuku session had to be started: the first control run reported `state=CHANNEL ready=true`, so the channel was already bound. The accessibility probe was therefore never invoked, and no accessibility setting was read or written (the only setting touched was `settings get system screen_brightness`, read-only, through the probe).

---

## 9. Verdict per experiment, and what did not work

1. **Experiment 1 — deterministic within the session.** 10/10 the same way at both 325 000 and 350 000, and 10/10 the same way at the tight boundary (346 482 / 346 874). Every failure was the same code, arm, class and reply shape. *Verdict: deterministic threshold, not probabilistic* — with the cross-session drift caveat in item 4.
2. **Experiment 2 — pass ≤ 346 482 B, fail ≥ 346 874 B (392-byte window, 10 runs at each endpoint), bracketed in wire bytes by 1 039 524 / 1 040 700.** *Verdict: established.*
3. **Experiment 3 — the ceiling is a character count.** The identical 400 000-byte request fails as ASCII and passes as CJK; the identical 540 000-byte request fails as 1-byte characters and passes as 3-byte characters; and the four arms' byte ceilings span 346 482 → 1 557 999 while their character ceilings all land in [519 034, 521 034]. libbinder's own transaction sizes match `align4(2 × JSON chars + 10)` exactly in all 22 distinct reply sizes measured. *Verdict: characters (UTF-16 units of the JSON reply), not UTF-8 bytes.*
4. **Cross-session drift is real and unexplained by the measurements here.** Same command, same build: 250 000 pass / 300 000 fail (2026-10-16), 325 000 / 350 000 (2026-10-17), 346 482 / 346 874 (this session). The transaction buffer is per receiving process and shared by every in-flight transaction, so the free headroom at reply time depends on the app's other Binder activity. I could **not** measure that occupancy (no root / no debugfs), so this stays a **hypothesis, not a measurement**; what *is* measured is that the app-side ceiling can sit a few hundred kilobytes below the size of the buffer itself.
5. **What did not work (reported, not hidden).**
   - **Two generator attempts were invalidated by quoting through `am broadcast --es command '…'`.** (a) A literal LF inside `tr -d '<LF>'` was parsed by the *device* shell as a command separator (`stderr` from the outer shell read `/system/bin/sh:  | head -c 540000: inaccessible or not found`), so the four `bA540`/`bC540` runs measured a broken command (`exit=1`, `stderrBytes=39`, `code=none`) — not the intended arm. (b) `tr '\0' 'a'` lost its inner quotes, so the remote ran `tr \0 a`, which mapped only `\`/`0`→`a` and passed the **540 000 NUL bytes through unchanged**; the reply was NUL characters, JSON-escaped to `\u0000` (6 chars each), which is exactly why the wire size was 6 480 080 B (12 B per output byte). The arithmetic proves the payload was NULs, not `a`. Those five rows are listed and marked invalid. Both problems disappear with quote-free, ASCII-only generators, which is what arms C/D use.
   - **The first binary-search batch's per-run rows were lost to a driver bug** (the probe's own output line was captured into the helper's return value). The decisions and wire sizes were recorded; the four missing sizes and the 10+10 endpoints were re-run with full rows (§7.3) and reproduced the same answers.
6. **Not measured / open.**
   - The precise metadata overhead between the documented `BINDER_VM_SIZE` (1 040 384 B) and the effective pass/fail line (somewhere in (1 040 080, 1 040 700] B) was not pinned further.
   - The CJK ceilings are bracketed to 2 000 B and 4 002 B rather than binary-searched to ~400 B like the ASCII one (time-bounded, one run per point rather than 10).
   - The threshold at which libbinder emits its `Large reply transaction` warning was not determined: it fires at ≥ 360 080 B of wire and did not fire for the 3–11 588 B commands, so it lies in between. It fires on **passing** transactions too (e.g. 1 039 524 B), so it is a warning, not a failure signal.
   - `truncated=true` was still never observed on a device: the link dies first at ~520 000 chars, far below the 8 MiB `MAX_REPLY_BYTES` memory bound — consistent with the existing R-9 record.
   - The remote user-service pid was sampled before and after a batch containing failures (`edith.phone:shell` pid 8274 both times, `edith.phone` pid 8044 throughout): the process that answers was still alive after the batch, i.e. recovery happened as R-9 recorded. This was **not** a per-failure sampling, so it does not add to R-9's mechanism claim.

---

## 10. What this means for the app

The safe reply size is a **character** budget, not a byte budget: the reply crosses one Binder transaction whose cost is `2 × (JSON-encoded characters) + 10` bytes, and the app process's transaction buffer is 1 040 384 B (1 MiB − 2 pages), shared with every other in-flight transaction of edith.phone. The measured line is **≈520 000 JSON characters (≈1 040 000 B of Parcel)**, with the tightest bracket at 520 034 chars pass / 520 345 chars fail; expressed in payload bytes that is ≈519 000 B of escape-free ASCII, ≈346 000 B of ASCII in which every second byte is a newline that escapes to two characters, ≈692 000 B of newline-terminated CJK and ≈1 558 000 B of unescaped CJK — the same wall, reached at byte counts that differ by 4.5×. A future tool that returns a large reply must therefore bound the **JSON-encoded character count** (and remember that escaping is part of it: a newline costs 2 chars, a control byte 6), keep a real margin below ~520 000 chars because the buffer is shared and the observed ceiling has drifted across sessions, and never read a UTF-8 byte count as the budget. Nothing the app issues today is remotely near it: the largest of its three state commands, `dumpsys package edith.phone`, costs ~11 622 JSON characters — about 2.2 % of the ceiling, a ~45× margin — and all three arrive with empty `stderr` (Experiment 4), so the state-read path has enormous headroom. Two product-relevant corollaries: Chinese-containing output (the Shizuku probes' case, and a future CJK-returning tool) is **cheaper** on this wire than its UTF-8 size suggests — 2 bytes per character against 3 UTF-8 bytes on the payload — so a bytes-based rule would over-restrict it by 3×; and `ShellReply.MAX_REPLY_BYTES = 8 MiB` should stay exactly what R-9 said it is, a memory bound, because the link fails at ~1/8 of it and the honest `SHELL_REPLY_TOO_LARGE` / `carrier` classification (not a silent truncation) is what the product must keep relying on.

---

## 11. Provision, credentials, privacy, git state

- **Nothing was installed, uninstalled, built or force-stopped.** The probe was already installed and the channel already bound (`state=CHANNEL ready=true` on the control run), so no `install -r`, no `gradlew installDebug` and no `connectedDebugAndroidTest` were run at any point. The 1.03 GiB hand-provisioned model set and the working DeepSeek API key were therefore never at risk; no APK was touched, so no signature check was involved, and no credential was read, printed or entered. Verified rather than assumed: the provision's data directory is still on the device (`run-as edith.phone du -s /data/data/edith.phone` → `1972855` KB ≈ 1.88 GiB, a size only, no content listed), and the `edith.phone` process kept the **same pid `8044` from the first control run (00:43:59) to the end of the session (00:58:03 onward)** — an app that had been reinstalled or force-stopped would have come back on a new pid. The API key itself was not exercised or inspected (nothing in this round makes a model call), so its presence is inferred from the untouched, un-reinstalled data directory rather than re-proven.
- **Glasses `1901092544027662` were never addressed** (every command in this round used `-s 2696813b`). Accessibility settings were neither read nor written, and the accessibility probe was never invoked because the channel was already live.
- **Privacy:** only counts, byte sizes, booleans, codes, exception class names, elapsed times, the fixed labels and the package name `edith.phone` are recorded. No command output, reply body, package list, user text or credential appears in this file or in the logs it cites; the probe does not log commands or reply bodies by construction.
- **Git:** no `git add -A`, no push, no merge, no tag, and **no commit** — this is the only file written, and it is left **untracked in the working tree**, matching how the earlier evidence files in `docs/project/evidence/` are currently left (`??` in `git status`). No production code, test or other document was modified.

---

# R-9 follow-up, open item #1: the exact budget

> §1–§11 above are the 00:43–00:58 session and are left exactly as they were written, including §8's "nothing was committed"
> and §11's "untracked in the working tree" — both were true of that session. That file was then committed as `76ec18e`.
> §12 below is a **new, later session** (01:19–01:31, same day, same device, same build, same probe) that closes open item #1.

## 12. The budget, exactly: **520 187 JSON characters**, and **260 076 B** of maximally-escaped raw output

- Date: 2026-10-17, device clock 01:19:50 – 01:31:18
- Device: Redmi K40 (`alioth`, serial `2696813b`), MIUI `V816.0.10.0.TKHCNXM`, Android 13 / API 33, page size 4096
- Repo: `D:\Project_new\EDITH`, branch `feat/0.21-agentloop-context-redesign`, HEAD `76ec18e`
- Instrument: the same existing debug probe `edith.phone/.DebugShellCapProbe` on the production `ShizukuUserServiceShell`. **No production code, no test and no new probe was written or modified.**
- **Process identity across both sessions:** `edith.phone` pid **8044** and `edith.phone:shell` pid **8274**, from the first control at 01:20:49 to the last run at 01:31:18 — the same two pids §9/§11 recorded for 00:43–00:58. Nothing was reinstalled or restarted.
- Privacy: only counts, byte sizes, flags, codes, exception class names and elapsed times are recorded. The generator command strings are this file's own fixed runbook. No command output, reply body, package list or credential appears here.

§9 left three things open: (1) the worst-case *byte* budget for heavily-escaped content, (2) whether `BINDER_VM_SIZE` really is a shared pool, (3) a CJK bracket tighter than 2–4 kB. **#1 and #3 are closed; #2 is reported as still unproven, with the instrument limitation that blocks it measured rather than asserted.**

### 12.1 The transport condition, now exact

Every one of the **34 reply sizes** measured this round satisfies the §2 model

```
wire = align4(2 × JSON_chars + 10)
```

with **0 mismatches** (checked programmatically over all 34 rows, not by eye), and the pass/fail line is exactly the buffer:

| | wire bytes | verdict |
|---|---|---|
| largest **passing** reply, all three arms | **1 040 384** | pass |
| smallest **failing** reply, all three arms | **1 040 388** | fail |

1 040 384 B is `BINDER_VM_SIZE` on this device exactly (1 MiB − 2 × 4096 B pages). **No passing observation exceeded it and no failing observation was at or below it.** §2 could only say the line lay in `(1 040 080, 1 040 700]`; it is now **at the documented constant** — which also disposes of §9's first open item (the "precise metadata overhead"): there is none; the straddle is `1 040 384 / 1 040 388`, and the earlier `1 040 700` fail was simply the next probe step, not evidence of a higher line.

In characters, `align4(2c + 10) ≤ 1 040 384` gives:

| JSON chars | `align4(2c+10)` | result | parity |
|---|---|---|---|
| 520 186 | 1 040 384 | **pass** | even |
| **520 187** | 1 040 384 | **pass** | odd |
| 520 188 | 1 040 388 | **fail** | even |

So the budget is **520 187 JSON characters pass, 520 188 fail** — a **1-character** window, where §4's pooled bracket was 311 characters wide. (The parity column is why: `2c+10` lands on a multiple of 4 only when `c` is odd, so an even `c` pays 2 extra bytes of alignment. The ceiling is therefore "≤ 520 187, attainable exactly when the count is odd".)

### 12.2 Arm A — high escape density: the worst case for a *byte* budget

Generator: `yes Ilwi | base64 -d | head -c N` — **quote-free**, per §9's warning. `Ilwi` decodes to the repeating 3-byte pattern `22 5c 22`, i.e. `"\"`, so **every payload byte is either `"` or `\` and costs exactly 2 JSON characters**. Byte pattern verified on-device with `od -An -tx1` before use (`22 5c 22 22 5c 22 22 5c 22` for the first 9 bytes). Here `JSON_chars = 2N + 34` and `wire = align4(4N + 78)`.

| N (requested = delivered bytes) | verdict | `stdoutBytes` | `exit` | measured wire B | JSON chars |
|---|---|---|---|---|---|
| 260 076 | **pass** | 260 076 | 0 | **1 040 384** | 520 186 |
| 260 077 | **fail** | 0 | −1 | **1 040 388** | 520 188 |

Both endpoints were then repeated 5× each, back to back: **10/10 the same way**, with the wire byte-identical on every run (1 040 384 ×5, 1 040 388 ×5). Every failure was the same shape as §3's (`exit=-1`, `stderrBytes=21`, `code=SHELL_REPLY_TOO_LARGE`, `arm=carrier`, `throwable=android.os.DeadObjectException`).

**Ceiling in both units: 260 076 B / 520 186 JSON characters pass; 260 077 B / 520 188 fail.**

The JSON-character figure is the point of the arm: it lands **inside** the 520 034/520 345 bracket that every earlier arm agreed on — at 520 186–520 188, i.e. the same 1-character window §12.1 and §12.3 independently reach. The byte ceiling is **half** of escape-free ASCII's 520 000 B, for exactly the reason §1 predicted: escaping doubles the character cost, and the wire prices characters.

**Derived worst-case raw-byte budget:** `(520 187 − 34) / 2 = 260 076.5` ⇒ **260 076 B** — precisely the measured pass point. A caller who thinks in raw output bytes and whose content is maximally escaped has a hard ceiling of **260 076 B**; at 260 077 B the reply does not cross.

For completeness, control bytes escape *worse* than 2 chars (`\u0000` is 6), so output dominated by NULs would cap at `(520 187 − 34) / 6 ≈ 86 692 B`. That is arithmetic on the same confirmed model rather than a new measurement — but §9's invalid NUL arm corroborates it independently: its 540 000 NULs predict a wire of exactly 6 480 080 B, which is the number libbinder actually logged.

### 12.3 Arm C — the CJK bracket, tightened from 4 002 B to **3 B**

Generator `yes 5Lit | base64 -d | head -c N` (`5Lit` → the 3 UTF-8 bytes of `中`), `N = 3k` so no character is split, `JSON_chars = k + 34`.

| k | N (bytes) | verdict | measured wire B | JSON chars |
|---|---|---|---|---|
| 520 152 | 1 560 456 | pass | 1 040 384 | 520 186 |
| **520 153** | **1 560 459** | **pass** | **1 040 384** | **520 187** |
| **520 154** | **1 560 462** | **fail** | **1 040 388** | 520 188 |

**Window: 3 bytes** (was 1 557 999 pass / 1 562 001 fail = 4 002 B) — tighter than the ~400 B asked for, and a 1-**character** window. This is the arm that reaches 520 187 exactly, so the character budget is bounded tightly in both directions.

### 12.4 A third arm, and what it settles about the "cross-session drift"

§3's tightest arm — `yes a | head -c N`, ASCII with a newline, `JSON_chars = 1.5N + 34` — was re-measured to test whether the ceiling had moved since 00:43–00:58:

| k | N (bytes) | verdict | measured wire B | JSON chars |
|---|---|---|---|---|
| 173 382 | 346 764 | pass | 1 040 372 | 520 180 |
| 173 384 | **346 768** | **pass** | **1 040 384** | 520 186 |
| 173 385 | **346 770** | **fail** | **1 040 388** | 520 189 |

§4 measured **346 482 pass / 346 874 fail**. The exact boundary, 346 768, sits **strictly inside** that bracket. So **between the two 2026-10-17 sessions the ceiling did not drift at all** — the earlier spread was bracket coarseness, not movement. The one reading on record that is genuinely lower (§3: 2026-10-16, pre-fix build, 250 000 B pass / 300 000 B fail in this same arm) is untouched by this round and its cause is still unmeasured.

### 12.5 Arm B — the shared-buffer hypothesis: **still unproven**

Three things were tried; none of them is a concurrency measurement, and the reason is now measured rather than guessed.

**(a) Two and three concurrent broadcasts — not constructible through this instrument.** Firing `am broadcast … & am broadcast … & … & wait` from the device shell produced strictly **disjoint** call windows:

| run | probe call windows (device time) | overlap |
|---|---|---|
| 2 concurrent | B2b 16.429–16.540, B2a 16.554–16.666 | none (14 ms gap) |
| 3 concurrent | B3a 29.099–29.221, B3b 29.233–29.361, B3c 29.372–29.475 | none (12 ms and 11 ms gaps) |

The reason is in the probe: `DebugShellCapProbe` calls `goAsync()`, so the broadcast is not finished until its worker calls `pending.finish()`, and AMS does not dispatch the next broadcast to that receiver until then. Each call begins only after the previous one *ended* (gap ≈ 0, spacing ≈ the previous call's own duration). **There is exactly one ingress path into `edith.phone` that this round can drive, and it is serialized by construction**, so two large replies cannot be put in flight together. The 2× and 3× runs both passing therefore says **nothing** about the buffer, and is reported only as the reason the test could not be built.

**(b) Full-buffer size back-to-back behind an oversized failure — 10/10 passes.** One device-side sequence with no inter-run delay, alternating an oversized reply with the exact pass point, ×10:

| arm | runs | pass | fail | wire, every run |
|---|---|---|---|---|
| oversized (`head -c 400000`, chars 800 034) | 10 | 0 | **10** | 1 600 080 |
| pass point (`head -c 260076`, chars 520 186) | 10 | **10** | 0 | 1 040 384 |

So a preceding **failed** 1.6 MB attempt does not compress the next call's headroom, and a reply that consumes **the entire 1 040 384 B pool** still crosses at back-to-back cadence. That bounds the "lingering occupancy" story — there is no measurable carry-over between successive calls — but successive ≠ concurrent.

**(c) Uncontrolled natural load (an observation, not a measurement).** Between 01:20:49 and 01:21:44 a **second workstream was driving this same probe continuously** — one IRemoteShell call every ~0.5 s, including `dumpsys package` replies of ~7.29 MB that failed (`wire 7 289 540 B`, `E/JavaBinder(8044): !!! FAILED BINDER TRANSACTION !!! (parcel size = 156)`). That is real traffic in the same app process, but it goes through the same serialized receiver, so it was serialized with my calls too — not a concurrency experiment. The small replies issued in that window (`3`–`5` B, and a neighbour `dumpsys` at 328 983 B) all passed normally.

**Verdict: neither supported nor refuted — unproven, because the available instrumentation cannot create two concurrent incoming transactions into `edith.phone`.** What *is* newly measured and consistent with the hypothesis (not proof of it): when nothing else was in flight, the entire 1 040 384 B pool was available to a single reply and the line sat exactly on `BINDER_VM_SIZE`, whereas every earlier reading sat strictly below it. "The pool is per-process and shared by the process's other in-flight transactions" therefore remains a **hypothesis**.

**Instrumentation collision, disclosed.** This round's first command ran `logcat -c`, which deleted the other workstream's evidence window before I knew it was there; from the second arm onward I stopped clearing and read with `logcat -d -T <since-time>` and unique labels instead, which is why its lines are still visible in §12.5(c). The other workstream's driver also clears logcat, so any of its arms that overlapped 01:20:49 may have lost a window to me. The parent agent was told at 01:21 and no further clearing was done.

### 12.6 Every run

`wire B` = the size libbinder's own `Large reply transaction of N bytes` line reported for that attempt (measured, not derived). Every row's `code=SHELL_REPLY_TOO_LARGE` also carried `arm=carrier throwable=android.os.DeadObjectException`; that never varied, so it is stated once here. **All 34 rows below satisfy `wire = align4(2 × JSON_chars + 10)` with zero mismatches.**

#### 12.6.1 Controls (probe + channel liveness)

| label | command (runbook) | `exit` | `stdoutBytes` | `code` | wire B |
|---|---|---|---|---|---|
| ctrl-1 | `settings get system screen_brightness` | 0 | 3 | none | — |
| ctrl-2 | `settings get system screen_brightness` | 0 | 3 | none | — |
| ctrl-3 | `settings get system screen_brightness` | 0 | 3 | none | — |

(No wire line: these are below libbinder's warning threshold, as in §6.)

#### 12.6.2 Arm A — high escape density, `yes Ilwi | base64 -d | head -c N` (`JSON_chars = 2N + 34`)

| label | N | verdict | `stdoutBytes` | `exit` | wire B | JSON chars |
|---|---|---|---|---|---|---|
| A260000 | 260 000 | pass | 260 000 | 0 | 1 040 080 | 520 034 |
| A260500 | 260 500 | fail | 0 | −1 | 1 042 080 | 521 034 |
| A260250 | 260 250 | fail | 0 | −1 | 1 041 080 | 520 534 |
| A260125 | 260 125 | fail | 0 | −1 | 1 040 580 | 520 284 |
| A260062 | 260 062 | pass | 260 062 | 0 | 1 040 328 | 520 158 |
| A260094 | 260 094 | fail | 0 | −1 | 1 040 456 | 520 222 |
| A260078 | 260 078 | fail | 0 | −1 | 1 040 392 | 520 190 |
| A260070 | 260 070 | pass | 260 070 | 0 | 1 040 360 | 520 174 |
| A260074 | 260 074 | pass | 260 074 | 0 | 1 040 376 | 520 182 |
| **A260076** | **260 076** | **pass** | 260 076 | 0 | **1 040 384** | 520 186 |
| **A260077** | **260 077** | **fail** | 0 | −1 | **1 040 388** | 520 188 |
| A261000 | 261 000 | fail | 0 | −1 | 1 044 080 | 522 034 |
| A260076r1–r5 | 260 076 | pass ×5 | 260 076 ×5 | 0 | 1 040 384 ×5 | 520 186 |
| A260077r1–r5 | 260 077 | fail ×5 | 0 ×5 | −1 | 1 040 388 ×5 | 520 188 |

#### 12.6.3 Arm AL — ASCII with newline, `yes a | head -c N` (`JSON_chars = 1.5N + 34`)

| label | N | verdict | `stdoutBytes` | wire B | JSON chars |
|---|---|---|---|---|---|
| AL173000chk | 346 000 | pass | 346 000 | 1 038 080 | 519 034 |
| AL173250 | 346 500 | pass | 346 500 | 1 039 580 | 519 784 |
| AL173375 | 346 750 | pass | 346 750 | 1 040 328 | 520 159 |
| AL173382 | 346 764 | pass | 346 764 | 1 040 372 | 520 180 |
| **AL173384** | **346 768** | **pass** | 346 768 | **1 040 384** | 520 186 |
| **AL173385** | **346 770** | **fail** | 0 | **1 040 388** | 520 189 |
| AL173386 | 346 772 | fail | 0 | 1 040 396 | 520 192 |
| AL173390 | 346 780 | fail | 0 | 1 040 420 | 520 204 |
| AL173406 | 346 812 | fail | 0 | 1 040 516 | 520 252 |
| AL173438 | 346 876 | fail | 0 | 1 040 708 | 520 348 |
| AL173500chk | 347 000 | fail | 0 | 1 041 080 | 520 534 |

#### 12.6.4 Arm C — CJK without newline, `yes 5Lit | base64 -d | head -c 3k` (`JSON_chars = k + 34`)

| label | k | N (bytes) | verdict | `stdoutBytes` | wire B | JSON chars |
|---|---|---|---|---|---|---|
| C520000chk | 520 000 | 1 560 000 | pass | 1 560 000 | 1 040 080 | 520 034 |
| C520150 | 520 150 | 1 560 450 | pass | 1 560 450 | 1 040 380 | 520 184 |
| C520152 | 520 152 | 1 560 456 | pass | 1 560 456 | 1 040 384 | 520 186 |
| **C520153** | **520 153** | **1 560 459** | **pass** | 1 560 459 | **1 040 384** | **520 187** |
| **C520154** | **520 154** | **1 560 462** | **fail** | 0 | **1 040 388** | 520 188 |
| C520155 | 520 155 | 1 560 465 | fail | 0 | 1 040 388 | 520 189 |
| C520160 | 520 160 | 1 560 480 | fail | 0 | 1 040 400 | 520 194 |
| C520169 | 520 169 | 1 560 507 | fail | 0 | 1 040 416 | 520 203 |
| C520188 | 520 188 | 1 560 564 | fail | 0 | 1 040 456 | 520 222 |
| C520225 | 520 225 | 1 560 675 | fail | 0 | 1 040 528 | 520 259 |
| C520300chk | 520 300 | 1 560 900 | fail | 0 | 1 040 680 | 520 334 |

#### 12.6.5 Arm B (concurrency / sustained load)

| run | arms | result | wire B |
|---|---|---|---|
| Bctrl1 (single 129 000 B) | 1 | pass, `stdoutBytes=129000` | 516 080 |
| B2x (two concurrent 129 000 B) | 2 | **both pass** — but windows disjoint, see §12.5(a) | 516 080 ×2 |
| B3x (three concurrent 129 000 B) | 3 | **all pass** — but windows disjoint, see §12.5(a) | 516 080 ×3 |
| B-rapid: oversized 400 000 B ×10 interleaved with 260 076 B ×10 | 10 + 10 | **0 pass / 10 fail** and **10 pass / 0 fail** | 1 600 080 ×10, 1 040 384 ×10 |

The 129 000 B size was chosen so that a *pair* is `2 × 516 080 = 1 032 160 ≤ 1 040 384` (fits) while a *triple* is `1 548 240 > 1 040 384` (does not) — the test would have been sharp had the calls overlapped. They did not.

### 12.7 Exact commands

```powershell
# --- the probe (unchanged, existing debug-only receiver) ---
adb -s 2696813b shell "am broadcast -a edith.DEBUG_SHELLCAP -n edith.phone/.DebugShellCapProbe --es label <label> --es command '<command>'"

# --- non-destructive read (no `logcat -c`: a neighbour workstream was driving the same probe) ---
$since = (adb -s 2696813b shell "date +'%m-%d %H:%M:%S.000'").Trim()
adb -s 2696813b logcat -d -v time -T "$since"          # then filter by the unique --es label
adb -s 2696813b logcat -d -v time -T "$since" | Select-String "Large reply transaction of"   # libbinder's own wire size

# --- control (proves probe + channel live; §12.6.1) ---
#   command: settings get system screen_brightness

# --- Arm A: high escape density (quote-free; `Ilwi` -> 22 5c 22 == "\") ---
#   command: yes Ilwi | base64 -d | head -c <N>          # N = 260000, 260062, 260070, 260074, 260076, 260077,
#                                                        #     260078, 260094, 260125, 260250, 260500, 261000
# --- Arm AL: ASCII with newline (the §4 arm, re-measured) ---
#   command: yes a | head -c <N>                         # N = 346000 .. 347000
# --- Arm C: CJK without newline (quote-free; `5Lit` -> e4 b8 ad == 中) ---
#   command: yes 5Lit | base64 -d | head -c <3k>         # 3k = 1560000 .. 1560900, k a multiple-of-3 boundary
# --- Arm B: concurrency attempt (device shell) ---
#   command: am broadcast ... --es label B3a --es command '<gen>' & am broadcast ... --es label B3b ... & ... & wait

# --- generator byte-shape verification, before use ---
adb -s 2696813b shell "yes Ilwi | base64 -d | head -c 9 | od -An -tx1"    # 22 5c 22 22 5c 22 22 5c 22
adb -s 2696813b shell "yes 5Lit | base64 -d | head -c 1560000 | wc -c"    # 1560000

# --- device facts ---
adb -s 2696813b shell getprop ro.build.fingerprint     # Redmi/alioth/alioth:13/TKQ1.221114.001/V816.0.10.0.TKHCNXM:user/release-keys
adb -s 2696813b shell getconf PAGESIZE                 # 4096  => BINDER_VM_SIZE = 1 MiB - 2 pages = 1040384
```

No Shizuku session had to be started (`state=CHANNEL ready=true` on every control). The accessibility probe was never invoked and no accessibility setting was read or written.

### 12.8 What this means for the app

The reply crosses one Binder transaction into `edith.phone`, whose whole incoming pool is `BINDER_VM_SIZE` = **1 040 384 B**, and the reply passes **iff** `align4(2 × JSON_chars + 10) ≤ 1 040 384` — measured, with the line sitting exactly on that constant and with **zero** exceptions across 34 reply sizes spanning three content encodings. The budget a tool author must design to is therefore **JSON characters after escaping: 520 187 is the last passing count and 520 188 fails** (a 1-character window, and the parity rule in §12.1 explains why an even count tops out 2 bytes earlier). Raw output bytes are *not* a budget, because the same 520 187 characters cost wildly different byte counts: **260 076 B** when every byte is `"` or `\` and escapes to two characters, **346 768 B** for ASCII text with a newline every second byte, **~520 000 B** for escape-free ASCII, and **1 560 459 B** for unescaped CJK — a **6.0× span**, all landing on the same line. So the number to carry in the tool face is the **worst case, 260 076 raw bytes** (which is what a caller might naively produce from an arbitrary command), backed by the invariant **520 187 JSON characters** for anything that wants to reason precisely; a caller should keep real margin under those, since (i) the pool is shared with the app's other in-flight transactions — a hypothesis this round could not test — and (ii) one earlier day's reading, on a pre-fix build, had this same arm's ceiling at 250 000–300 000 B, i.e. a worst-case raw budget as low as ~187 500 B. Finally, `ShellReply.MAX_REPLY_BYTES = 8 MiB` is confirmed to be exactly what R-9 said — a **memory** guard, not a transport limit: the transport dies at ~0.5 M characters, i.e. roughly 1/8 of it in the most favourable encoding and 1/16 in the worst, so `truncated=true` is unreachable in practice and the honest `SHELL_REPLY_TOO_LARGE` / `carrier` classification stays the thing the product relies on. Nothing the app issues today is near any of this: the largest of its three state commands, `dumpsys package edith.phone`, costs ~11 622 JSON characters (§6) — about **2.2 % of 520 187**, a ~45× margin, and the 6× cheaper direction on this wire than its UTF-8 size suggests.

### 12.9 Provision, credentials, privacy, git state

- **Nothing was installed, uninstalled, built or force-stopped.** No `install -r`, no `gradlew installDebug`, no `connectedDebugAndroidTest` (the probe was already installed and the channel already bound). The 1.03 GiB hand-provisioned model set and the working DeepSeek API key were therefore never at risk, and no signature check was ever involved. **Verified rather than assumed:** `run-as edith.phone du -s /data/data/edith.phone` → `1972855` KB ≈ 1.88 GiB — the *same* size §11 recorded for the earlier session, a size only, no content listed — and `edith.phone` kept pid **8044** and `edith.phone:shell` pid **8274** for the entire round (01:20:49 → 01:31:18). An app that had been reinstalled or force-stopped would have come back on a new pid. The API key itself was neither exercised nor inspected (nothing in this round makes a model call), so its presence is inferred from the untouched, un-restarted data directory rather than re-proven.
- **Glasses `1901092544027662` were never addressed** — every command in this round used `-s 2696813b`. Accessibility settings were neither read nor written.
- **Privacy:** only counts, byte sizes, booleans, codes, exception class names, elapsed times, pids, the fixed labels and the package name `edith.phone` are recorded. No command output, reply body, package list, user text or credential appears in this file or in the logs it cites; the probe does not log commands or reply bodies by construction.
- **Git:** explicit paths only (no `git add -A`), no push, no merge, no tag. This round writes exactly two files — this evidence file (new §12) and `docs/project/CONTRACTS.md` (new §F.1) — and no production code, test or other document was modified.
