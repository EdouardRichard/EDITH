# EDITH P2 Inline Execution Ledger

checkpoint: p2-exec-20261008-45

## D45 (merge into main) - P2 delivered to main; three defects only the delivered form exposed

Per the user's instruction ("将变更合并入main分支。P2闭环。"), the P2 branch was committed and merged into `main` with four
`--no-ff` merges: `a0dd455` -> `7b58c8d`, `db208c6` -> `c6ac556`, `3156f0e` -> `e1ad250`, `0b73554` -> `ae80af3`.
`main = ae80af3`, `codex/p2-public-speech-text = 0b73554`, trees identical after every merge, **nothing pushed**
(`origin/main` still `f28b245`).

Building the merged tree from the canonical root checkout - rather than only from the worktree - caught three
delivery-blocking defects, all now fixed in main:

1. the vendored-AAR hash check was pinned to the worktree layout (`rootProject.file("../../probes/p0/vendor/…")`, which
   resolves outside the repository from the root);
2. `settings.gradle.kts` declared the ivy repository with the same pinned relative path, so the CXR artifact could not be
   resolved from the root even after (1);
3. the unanchored `.gitignore` pattern `Audio/` matched the `edith/audio/` **package** directory (ignore matching is
   case-insensitive on Windows), so **16 audio-android sources/tests had never been tracked**: a fresh checkout compiled
   that module as NO-SOURCE and failed in phone-app. The pattern is root-anchored now and the 16 files are tracked.

Validation on the delivered tree (from `D:\Project_new\EDITH`, main @ `ae80af3`): BUILD SUCCESSFUL and
**16 modules / 764 tests / 0 failures / 0 errors / 0 skipped**. The root's pre-merge stale ledger copies were stashed
(`stash@{0}` = `908f0c2`). New completion criterion: done means green in the delivered form, not only in the worktree.
Evidence: `docs/project/evidence/2026-10-08-p2-merge-to-main.md`.

checkpoint: p2-exec-20261008-45

## D44 (after delivery) - the lesson became a project-wide 铁律; AGENTS.md drift resolved

Per the user's instruction, `AGENTS.md` (root + this worktree) now carries a new 铁律: the glasses<->phone App
interconnection must be 100% per the official demo and `SKILL.md` (session/authorisation, custom-command send/receive, the
callback **layer**, subscribe form, `Caps`/payload fields and order), and demo/official-doc-conformant interactions are
high-confidence with **no dedicated device verification required**. It records the evidence (the earlier "direction unusable"
verdict was our own divergence - session-layer registration plus a self-invented payload - and demo-aligned code worked on the
first device run), the procedure (SKILL.md is a **routing** doc -> bundled references -> vendor samples -> `javap` the pinned
AARs), the narrow platform-API allowance where the vendor has none (display wake, with its official basis), and the
exemptions (hardware/power/product behaviour, worn readability, TTL/disconnect still need measurement; credentials, D33/D36
and upload limits unchanged; unmeasured stays unmeasured). The worktree copy was brought up to date as well (missing second
铁律 clause, the 「安卓APP目标载体区别说明」 section, entry item 6): **both files are now byte-identical** (SHA256 `0FC0012F…`,
7173 B) - the Addendum P drift is closed.

checkpoint: p2-exec-20261008-43

## Current Recovery / Checkpoint43

**A group**: A1 already on the device (`DeepSeek · deepseek-flash`, `文本实测：已验证`; key in Keystore, **not read**; host config values not read, names only). A3 D31 controlled synthetic probe attempt 3 -> `exit 0` / `STATUS_COMPLETED` for the fixed input `只回复测试通过` (raw output `.local/p2-synthetic-probe-manual.out`, SHA256 `5480A789…`), **connectivity only**. Controller-script defect recorded: PowerShell 5.1 made java's `JAVA_TOOL_OPTIONS` stderr notice a terminating error under the script's own `Stop` preference, so its timestamped JSON was never written; the run was completed by direct invocation. A3' in-app probe: consent gate worked (dialog captured), user consented, but **outcome not objectively captured** (no persisted record, `已验证` label pre-dates the run, polling displaced by a system volume dialog) - neither pass nor fail. A4/latency/engine/T19 **not run**: the corpus is carried by the ASR path which P2 deliberately left unwired (D39; plugins are shells), and no real run document exists. **P2 has reached its verifiable boundary without a scope change**; going further needs explicit approval. Evidence: `docs/project/evidence/2026-10-08-p2-t19-model-verification-and-p2-boundary.md`.

checkpoint: p2-exec-20261008-43

## Current Recovery / Checkpoint42

**B1 (T17 controlled format) measured on the glasses**: `.local/p2-t17-bringup-run.ps1 -RunName p2-t18-format-2` ->
`monoPcm16Le=true, mismatch=null`; 384,000 B in 12,128 ms; measured 31,662.3 B/s vs declared 32,000 (98.9%); blocks exactly
640 B = 20 ms; RMS 688.2 (threshold 300); analysed window 3 s at offset 128,000 B. The format is now **measured rather than
decided**. The first run (`p2-t18-format-1`) was a negative that explained itself: full-rate frames with `mismatch=LEVEL_MISMATCH`
(RMS < 300) because the speaker's voice missed the 12 s window; the re-run with immediate sustained speech passed. Both recorded.
Side evidence: the CustomApp was **our own `edith.glasses`**, not the vendor demo, and PCM still arrived at full rate.
**`verified-format.json` was NOT written** (D38 unchanged): its consumer needs that file plus an on-disk `authorizedPcmFile`
(raw-audio persistence, not authorized by D33), the engine bundle and `runNativeCandidateTests=true`. **T16 real numbers stay
deferred and unmeasured.** D33: 12 s PCM in RAM only, zeroed on exit, nothing on disk, nothing uploaded.
Evidence: `docs/project/evidence/2026-10-08-p2-t17-controlled-format-verified.md`.

checkpoint: p2-exec-20261008-42

## Current Recovery / Checkpoint41

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06. Approved r3/plan-set-r1/Inline unchanged. Both pinned AAR hashes untouched; no commit/push/merge; no credential read.

**Device closure (user present, cooperated)**: T18's loop is **verified on the real glasses** - `HELLO/PING/SYNC/SHOW/CLEAR` out, `PONG`/`ACK` back, `mapping live ... syncSent=true` (interval width 31-98 ms), `Show displayed: sequence=1..7`, `inbound: Ack` per hint, wearer confirmed readability. D41 needed only demo fidelity; this is stronger.

**Three device-only defects, each RED first, then fixed**: (1) per-plugin storage was built for every activation, so the namespace whitelist refused the debug probe with `UNKNOWN_PLUGIN_NAMESPACE` - now resolved only when the plugin requests Storage, and the rig reproduces production's whitelist; (2) `ClockMapping.fromSync` anchored at 0 while `isExpired` measures elapsed time, so every `SHOW` was refused as "mapping expired" on a real clock (glasses uptime ~145,000 s) - the anchor is now the local instant and `establishedAtNanos` is required; (3) `RokidSessionTransport` never reset `connected`, so the Connect button became a permanent no-op after the AI App ended the session - reset in `interrupt()`, device-verified.

**D42 (user instruction + approved scope change)**: on-demand display wake - the panel is woken by a pushed screen message and held only while the hint is on screen (15 s bound), released on expiry/clear/disconnect. Vendor SDK has no wake API (official FAQ: `FLAG_KEEP_SCREEN_ON` only; ring `KEY_POWER` cannot be intercepted), so it uses the standard Android screen wake lock; the glasses app now declares `android.permission.WAKE_LOCK` (supersedes D40's zero-permission baseline for this one item). Objective evidence: `Asleep`/`size=0` -> `Awake` + `'edith:hint' ACQUIRE_CAUSES_WAKEUP` -> `Asleep`/`size=0`.

**Verification**: 16 modules / 764 tests / 0 failures / 0 errors / 0 skipped; both APKs installed (phone `69DE7E56…`, glasses `8817B397…`). **Recorded, not fixed**: 诊断页 capability rows are a construction-time snapshot; hint bursts queue with reduced remaining TTL; product-level rows deferred (D38). Evidence: `docs/project/evidence/2026-10-08-p2-device-closure.md`.

checkpoint: p2-exec-20261008-41

## Current Recovery / Checkpoint40

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06. Approved r3/plan-set-r1/Inline unchanged. **No device operation after D41**; both dependency hashes pinned and untouched.

**D41 (user)**: "检查当前双端app代码，所有眼镜端与手机端的交互都百分百按照官方demo来实现，且不需要实机验证，按官方demo的就是正确的原则简化TDD。" Read as: **every** glasses<->phone interaction must follow the official demo 100%; demo fidelity is the correctness criterion; no device verification for that; simplify the TDD accordingly.

**Aligned four divergences** (details in `docs/project/evidence/2026-10-08-p2-demo-fidelity-alignment.md`): phone sends via the **client** (`sendCustomCmd("rk_custom_client", Caps{write("rk_custom_key"); write(payload)})`) instead of the session wrapper with a one-string Caps; phone registers inbound with the **client** `setCXRCustomCmdCbk` instead of `CxrSession.addCustomCmdCallback` (session only a fallback, never both); phone parses a reply as a **serialized `Caps`** (`Caps.fromBytes`, payload = second string) instead of requiring `{`-prefixed bytes (that test dropped every real reply); glasses use the demo's **`MsgCallback`** and reply with `Caps{write("message"); write(payload)}`.

**Corrected**: the earlier conclusion that the glasses->phone direction was unusable is withdrawn as a statement about the vendor's design - both negatives were measured with the phone-side registration the demo does not use plus our own payload shape. Recorded by appending a correction to the original evidence file.

**TDD simplified**: `CxrInboundPayloadTest` 5 -> 3, `CxrCommandDeliveryWiringTest` 6 -> 3; routing coverage retained via `BinaryChannelRoutingTest`/`CxrBackendTest`, which now drive whichever layer is installed. `HintClockSync` tests kept.

**Verification**: 16 modules / 760 tests / 0 failures / 0 errors / 0 skipped; phone + glasses debug APKs BUILD SUCCESSFUL (phone `5DCD4BA2…`, glasses `794A272A…`). No device verification claimed; the phone holds the previous build (recorded, not a gap). Product-level device rows (worn readability, on-device TTL, real engine, real text loop) stay deferred and unmeasured under D38.

checkpoint: p2-exec-20261008-40

## Current Recovery / Checkpoint39

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06. Approved r3/plan-set-r1/Inline unchanged. **No device operation this increment**; `client-l:1.1.2` hash-pinned and untouched.

**D40 (user)**: "完成P2代码开发，以及不依赖人工测试的内容。需要人工配置测试的内容先标记。后续再验证。" Manual/device items are marked in `docs/project/P2_MANUAL_VERIFICATION.md` (A: manual configuration, B: human presence, C: device runs). Nothing there was executed.

**Done**: (1) **`glasses-app` built** - the last unwritten file set in P2 (plan Task 18), previously parked on the user's architecture decision: module + manifest (no permission at all) + `GlassesActivity` (fixed safe-area TextView, real clock, local TTL) + `RokidBridgeEndpoint` + `GlassClockResponder` + `HintPresentation`, sharing `output-runtime` with the phone so the protocol has one implementation. Bridge symbols read from the pinned AAR with `javap`; no `unsubscribe` invented; acks sent as a fresh reply-channel message. (2) **PING -> PONG implemented** - a real protocol gap: the controller ignores PING, so without it no mapping could ever exist and every hint was correctly refused as unmapped. (3) **T19's 6 fault-injection cases moved to the JVM and made to actually run** - 5 of 6 had never passed, because the rig submitted to its own fake sink while `HostRuntime.accept()` is only a predicate (never reaching the arbiter); fixed by capturing the sink the host hands the plugin, plus one impossible case (`Assistance` forbids a negative deadline) and one wrong assertion. 6/6 now pass.

**Verification**: full regression **16 modules / 746 tests / 0 failures / 0 errors / 0 skipped**; `:glasses-app:testDebugUnitTest` 9/9; `:phone-app:testDebugUnitTest` 107/107; phone and glasses debug + androidTest APKs BUILD SUCCESSFUL; tooling `report-latency.tests.ps1` 12/12 and `report-engine.tests.ps1` 10/10. No instrumentation, no lint, no release packaging. P2 code is complete; only `evidence/2026-10-06-p2-text-loop.md` remains, and it needs the real run. Evidence: `docs/project/evidence/2026-10-08-p2-software-completion.md`.

checkpoint: p2-exec-20261008-39

## Current Recovery / Checkpoint38

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06. Approved r3/plan-set-r1/Inline unchanged. **No device operation this increment** (D38 defers all device/human verification to the end); `client-l:1.1.2` hash-pinned and untouched.

**D39 (user chose "(a)")**: wire the App's `services(...)` as its own item and decide the activation timing in the same change. `CxrHostEnvironment.services(...)` returned `EmptyHostServices`, so neither shipped plugin could pass the capability gate and a hint could never reach the glasses.

**Done**: `availableCapabilities` is now derived from the providers that actually exist (Model, per-plugin Storage, and TextOutput only while a clock mapping is live), so advertised == answerable is structural and pinned by a test; `services(...)` assembles providers per activation because storage is per plugin; `TextOutput` cannot be caller-supplied. `Audio`/`Asr`/`SpeechOutput`/`Speaker` are deliberately not wired (microphone path needs its own approval + D33 reminder; Speaker would decode microphone audio against the decision-labelled PCM format and enrolment is not authorized), so `interview-assistant` still refuses. Plugin background work runs on a dedicated composition thread whose `cancelAll` cancels pending work rather than shutting the executor down.

**Activation timing decided**: retry activation once when the display goes not-live -> live, only if an activation was actually refused for capabilities, only while `Paused`, never after user pause/stop, not on renewals; a successful retry clears the earlier `CAPABILITY_MISSING`.

**Impact**: `HostRuntime` untouched; `smart-assistant` activation reachable for the first time; `capabilityReasons` no longer lists Model/Storage as missing (test updated to the new truth); microphone path and speaker enrolment untouched; public contracts unchanged; the shipped plugins are shells, so this increment submits no hint. **Tests +8; regression 15 modules / 731 tests / 0 failures / 0 errors / 0 skipped**; `:phone-app:assembleDebug` rebuilt; instrumentation not executed. Evidence: `docs/project/evidence/2026-10-08-p2-t18-plugin-services.md`.

checkpoint: p2-exec-20261008-38

## Current Recovery / Checkpoint37

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06. Approved r3/plan-set-r1/Inline unchanged. **No device operation this increment** (D38 defers all device/human verification to the end): no install, no capture, no K40/glasses command; `client-l:1.1.2` hash-pinned and untouched.

**D38 (user, 2026-10-08, verbatim)**: "T16判定降级。已经过官方demo验证可以收音，就可认定百分百通过了，其余人工参与的验证，推迟到最后。" T16's acceptance criterion is downgraded - official Demo audio capture + our measured byte rate is enough to judge T16 **passed** - and every human-in-the-loop verification is **deferred to the end**. **Not a measurement**: the controlled-vowel format capture was never obtained (`official-spec+user-decision`, still claims no measurement), and the real engine numbers (RTF P95 <= 0.7, no growing backlog, semantic accuracy >= 90%, 60 s/600 s run) were **never measured** - deferred, not passed, and no number may be cited as measured evidence. `verified-format.json` was **not** synthesized and `EngineBenchmarkTest`'s gate is untouched, so it still blocks a forgery when the deferred run happens. D33 / D36 scope / credentials / upload limits unchanged.

**T18 wiring landed (software, all four approved points)**: `CxrHintLink` owns the device and reads the lazily built connection through a holder; `HintClockSync` drives HELLO/PING/PONG/SYNC, publishes the interval in the glasses' clock domain and renews via the composition's scheduler; inbound acks are routed from the shared `rk_custom_key` channel by content; the display is advertised only while a live mapping exists (no generated id can impersonate a mapping - the fallback is the explicit `UNSYNCED_SYNC_ID`). A fire-and-forget transport send seam was added because the existing `send` claims its reply channel for the connection's lifetime. `HostRuntime` and all call sites except `HostCompositionRoot.create` untouched; `services(...)` deliberately not touched; `HostEnvironment.input` stays refusing so no microphone-start path was added; public contracts unchanged.

**Real defects found and fixed, each with a test**: an ack arriving before `show()` returned was dropped; fixing that exposed a `[Displayed, Queued]` status inversion; and `HostRuntime` reads `environment.output` once at construction, so liveness had to move into a stable forwarding device. Also fixed: readiness arriving before a session start was dropped permanently.

**Tests +17; regression 15 modules / 723 tests / 0 failures / 0 errors / 0 skipped**; `:phone-app:assembleDebug` and `:phone-app:assembleDebugAndroidTest` BUILD SUCCESSFUL; no Java processes. Evidence: `docs/project/evidence/2026-10-08-p2-t18-hint-link-wiring.md`. Deferred and not verified: the glasses app answering PING, SDK-level send acceptance, real TTL/display/human visibility, the controlled-format artifact, T19.

checkpoint: p2-exec-20261008-37

## Current Recovery / Checkpoint36

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06b3f98786200433b671fd631b287b507b. Approved r3/plan-set-r1/Inline unchanged. **No device operation this increment**: no install, no capture, no K40/glasses command; `client-l:1.1.2` hash-pinned (`4E79963F...DAF25` in `transport-rokid/build.gradle.kts`) and untouched.

**Addendum Q - the controlled-capture `SPECTRUM_MISMATCH` root cause is an analyser bound, and it is fixed.** `PcmFormatEvidence.measureWindow` accepted at most 96,000 bytes (3 s at 16 kHz mono PCM16) and returned `null` above that; `ControlledToneFormat.verifyVoiceBurst` mapped that `null` onto `SPECTRUM_MISMATCH`. The device harness records up to 12 s (384,000 B), so the waveform, level and activity checks were **never reached** in any device run. The only two retained device verdict lines are both 384,000 B (`bytes=384000 ... mismatch=SPECTRUM_MISMATCH`, in `audio-fix-1/phone.holder2.logcat.txt:942` and `phone.vowel.logcat.txt:4938`); the other recorded refusals were 316,160 B and 315,520 B, i.e. every refused capture exceeded the bound. The amplitude figures (±17/±13, later −402/+543) came from a separate, now-deleted diagnostic that never fed the verdict. The cap had zero test coverage (largest fixture 48,000 B) - the same class of gap as the fixture that never modelled `startAudioStream`.

**Correction, not deletion.** The earlier record (P2_PROGRESS addenda G/H/O, ACCEPTANCE, and `evidence/2026-10-08-p2-t17-audio-unblocked.md`) described this verdict as near-silence/stimulus. Accurate statement: it was structurally unreachable. T17 stays closed and delivery/format stand (`official-spec+user-decision`, explicitly not a measurement). New evidence: `docs/project/evidence/2026-10-08-p2-t17-spectrum-mismatch-root-cause.md`.

**Fix under TDD (no device).** Byte rate still whole-capture vs wall clock; waveform now over the strongest bounded ≤ 3 s window inside the capture (1 s hops, earliest wins on ties, tail tried for non-hop-aligned lengths), reporting `analysedWindowOffsetBytes` / `analysedWindowDurationMillis`. The two `null` paths became `checkNotNull`. Files: `PcmFormatEvidence.kt`, `ControlledToneCapture.kt`, `VoiceBurstFormatTest.kt`; no public contract changed. RED: compile RED then an executed-assertion RED reproducing the device string exactly (which is also the mutation check); GREEN: `VoiceBurstFormatTest` 17/17, `ControlledToneCaptureTest` 8/8. **Aggregate 15 modules / 706 tests / 0 failures / 0 errors / 0 skipped** (+5 over 701). No Java processes; Gradle released.

**Boundary.** T16 **still blocked**: `verified-format.json` still requires a genuine controlled capture and none has been taken; its path is now physically reachable for the first time. The two user-owned options are unchanged: (a) one genuine controlled capture, (b) re-scope what T16's numbers may claim. T18 composition still awaits approval of the four design points (lifetime/owner, live `ClockMapping` `syncId`, scheduler, inbound ACK routing); T19 untouched. Next step needs the user and a device-operation reminder.

checkpoint: p2-exec-20261008-36

## Current Recovery / Checkpoint30

Only P2; worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, HEAD e5b82f06b3f98786200433b671fd631b287b507b. Approved r3/A/B/C/Inline bodies freshly match70e8d96 after status/checkbox/newline normalization. Preserve staged/unstaged changes. User latest correction authorizes T15 model form repair; it does not authorize new protocols/network/media capabilities.

T15 real user attempted App configuration but save was rejected by generic validation. Exact offending field is unknown; source merges required fields, URL, number conversion and capacity conflicts. Priority: safe field-specific errors/save retry; maximum input/output in K tokens, automatic internal budget, Responses API naming. Keep API1/persisted protocolId and tested subset unchanged. User is present for later fixed speech/glasses checks; never inspect live unsaved credentials. Existing c29 UI7 and stable layout-state1 are old-version device evidence, not proof of this forthcoming correction. Keystore/process stored evidence retained; actual model profile/probe still open.

Actual T15 correction is isolated ephemeral Codex CLI gpt-6.1-sol/high, provider custom, session60580/thread01a11562-dd80-7621-b15c-fad7ee31e3ad, requirements.local/p2-t15-user-correction-context.md; report.local/p2-t15-user-correction-report.md. It owns exclusive D-local Gradle and T15 files only; no device/key/next-task grant. First ignore-user-config CLI runs exited1/401 before implementation; v2 uses existing provider config, no credentials read. Collect v2 report/exit before new build.

T17 software saved at safe boundary:15transport+51phone final XML zero failure/error, final buildexit0/50s; phone/transportTestAPK hashes in task-17-report.md, not installed. Scoped independent read-only CLI review session47653, requirements.local/p2-t17-review-context.md/report.local/p2-t17-review.md, immutable diff E769CECF156734006D8781A1A32E74958DF97E047613717F27CAB76CB60D1F4B; pending verdict. Command channel reuse currently blocks repeated T18 traffic, real App assembly and PCM controlled validation remain open. Format resolve staysnull, no native ASR/engine selection allowed.

T16 measurement audit NOT READY: raw-file fixture conflicts with D33 RAM-only grant, runner/reporter schemas differ, speaker work/complete windows/failure records/annotations/semantics/RSS/calibration missing. Repair measurement tooling after T15/T17 review; do not launch existing runner on real audio. K40 is current acceptance device under D37; no extra K70 requirement inferred from historical text. Native software scoped PASS/I4 scoped PASS remain narrower. Frozen c29 APK has16license/NOTICE entries matching sources,20native libraries; distributionReview remainsIncomplete. C top-level.android/.gradle no newly changed files since07:25UTC; new outputs D-only, no broad cache cleanup.

Next collect T15 correction and T17 review, resolve source findings, rebind/install corrected phone/testAPK with20s popup response/480s total bounded monitor, validate synthetic UI then user configuration. Then remaining T17 format, T16 RAM benchmark, T18/T19 within P2. No new capture/inference/request/upload/key import/enrollment. P2 incomplete, P3 not started. This checkpoint records running work and does not pause it.

## Historical Recovery Entries


checkpoint: p2-exec-20261007-28

## Current Recovery / Checkpoint28

Only P2. Worktree D:/Project_new/EDITH/.worktrees/p2; codex/p2-public-speech-text; HEAD e5b82f06b3f98786200433b671fd631b287b507b. Approved r3 / plan-set-r1 / Inline persists.

T15 stored finalUI7/7/process prepare1/recover1/PID assertion/Keystore6 accepted within scopes; missing during-test settings/complete layout and real App config remain. T16 stored audio-android20/audio-runtime77/report10; pointer recovery/ownership/config/embedding/retention implemented. Fresh local-Ivy AAR packaging and unit20 GREEN exit0/50s, exact public hash unchanged. JNI bridge continuation and I4 independent review now dispatched, exclusive D-local Gradle belongs to native implementer. Missing-type compilation REDs remain distinct from executed retention/reporter behavioral REDs. No task completion or commit. Details: docs/project/evidence/2026-10-07-p2-t16-software.md.

Next finish T16 software, reconcile remaining T15 gates, then T17 format before T16 real benchmark. T17-T19 unimplemented, P2 incomplete, P3 not started. No new device/capture/native/model/request/upload/key/import/enrollment operations. New authored outputs D-local. No inherited Gradle remains.

## Historical Entries


checkpoint: p2-exec-20261007-24
## Current Recovery / Checkpoint24

- [x] P2-T01-T14 complete only in recorded scopes; T14 Android fake-provider Denied1/Granted4 accepted.
- [ ] P2-T15 InProgress; actual Keystore6 PASS, editor2 FAIL, fix4 running and acceptance pending.
- [ ] P2-T16-T19 unimplemented; P2 incomplete, no P3.

Approved baseline remains public API1 / P2-DESIGN-r3 / r3/A/B/C / Inline Execution, normalized against 70e8d96; D31-D37 persist. Worktree D:/Project_new/EDITH/.worktrees/p2, branch codex/p2-public-speech-text, software HEAD e5b82f06b3f98786200433b671fd631b287b507b. T01-T14 are complete only within recorded scopes; T14 actual Denied1/Granted4 uses fake input/model.

T15 remains InProgress: fix1 scoped PASS/I1 ADDRESSED and fix2+3 scoped PASS/I2-I4 ADDRESSED, no new Critical/Important. Frozen fix23 evidence has 34 sources plus 2 signed/privacy-checked APKs; recovery rehash 36 entries/0 mismatches is stored-evidence verification. Actual K40/API33 Keystore6/6 PASS proves AndroidKeyStore/cipher and same-process fresh vault reconstruction; process-death remains unverified. Actual editor2 executed/2 failed on descendant-label discovery; collector TestCount0 means zero accepted successes. Original software44, fix1 software35 and actual Keystore6 are separate runs; no new run is claimed.

Fix4 is running: isolated CLI exec8123/session01a11357-459b-7a70-8e9d-23e10dff9acc, gpt-6-astra/high, exclusive D-local Gradle, only ProfileEditorTest.kt and local evidence. Report/exit/scoped review/new source-package bindings/actual UI GREEN remain pending. Controller owns future task acceptance, native C ledger, devices and commit. Next collect .local/p2-t15-device-fix4-report.md, review, bind/install matching packages and execute required UI flows. Fix3 producer failure is p2-t15-device-fix3-package.log; package-final.log is successful40s. Prior fix1 small-screen/large-font captures do not certify final fix23/fix4 packages; display settings were restored.

Controller's latest inventory records K40 2696813b/API33 and glasses 1901092544027662 online; K70/Android16 remains the product target. Same-signature no-popup behavior is an observation; exact-target confirmation detection retains the 20s popup-response deadline, 480s total-install cap and on-demand wake. Tests use fixed synthetic secrets/provenance; no new capture, model request, D36 upload, credential import or enrollment occurred. Required UI/configuration and lifecycle gates remain open; offline lint/release checks remain later work. T16-T19 unimplemented, P2 incomplete, P3 not started. This checkpoint records running work and does not pause it. Durable details: docs/project/evidence/2026-10-07-p2-t15-review.md; reconciliation: .local/p2-t15-recovery-audit.md.

checkpoint: p2-exec-20261007-23
## 历史入口（checkpoint22；不定义当前状态）

- [x] P2-T14 Android host：e655645 + debug fix2 35fdc32 + test-only foreground fix3 e5b82f0；scoped spec/quality PASS、真实 Android fake-provider Denied1/Granted4 通过，原 309/fix64/helper4/device1+4 分开保留。
- [ ] P2-T15 Keystore/profile UI：InProgress，实际 native /root/p2_t15_implementation，base e5b82f0，exclusive D-local Gradle；报告/独立审查/controller dummy Keystore/UI 验收待收取。

当前任务状态以本段为准；下方旧 checkbox 仅保留历史。

T01-T13 软件完成；T14 fix3 已独立 spec/quality PASS，K40/API33 实际 Denied 1/1、Granted 4/4 正常通过，仅 real Android 服务/权限与 fake input/model 范围。T15 软件已构建并完成存量 44 测试/双 APK 绑定核对；独立审查发现首写失败后的新 alias 清理 Important，修复轮次正在进行，尚无修复报告或复审结果。Keystore 实机 provider、UI、真实配置探针仍未验收；T16-T19 未实施。

真实 PCM/native/provider、格式、RTF/语义/声纹质量、持久配置/Keystore、双向 ACK/TTL/佩戴可见与 T19 闭环仍开放；P2 未完成，P3 未启动。无采音、模型请求、D36 上传或新注册；本次仅同步存量证据，未重跑验收，checkpoint 不停止执行。

## 历史记录（checkpoint20 及此前；不定义当前状态）

checkpoint: p2-exec-20261007-20

Current checkpoint20: software35fdc32/T14 reviewed packagingfix2; current installed app/test confirmed but0devicepasses. Actual foregroundfix3 isolatedCLI/session1767/thread01a11303-87a7-7341-b273-2e87c1d09fa7/gpt-6.1-sol high exclusivelyDGradle; collectreport/exit/review/actualDenied1Granted4 beforeT15. OperationalUSBhelperactualpopupverified5957ms/1tap/exit0Success/pmpath;20sreaction separatefrom480stotalinstall cap. Nosecuritysettingchange/capture/network;P2incomplete/noP3. Durableevidence docs/project/evidence/2026-10-07-p2-k40-install-runtime.md; nativeCledger exactpath.superpowers/sdd/2026-10-06-p2-c-android-device-loop/progress.md. Historicalcheckpoint19below.

## 历史恢复位置（checkpoint19）

只推进 P2；工作区 D:\Project_new\EDITH\.worktrees\p2，分支 codex/p2-public-speech-text，T14 软件提交 e655645a4de519420a04f3093cbb082af65887d3（22 个任务文件）。T14 软件已接受并提交；DEVICE TESTS PENDING。fix1 已交付并释放 Gradle，独立 scoped 复审 I1/I2 ADDRESSED、无新增 Critical/Important/Minor；T15 派发待 controller 更新，T15-T19 未实施，P2 未完成，P3 未启动。

原 T14 34 suites/309 tests/零失败与 fix1 10 suites/64 tests/零失败分别保留；fix1 compiled RED 1 test/1 expected failure → GREEN，package 成功为独立命令，本次记录审计没有重跑。修复 diff SHA256 18D0760F2045356C63F2958FF7ECA5DA53CE2CE9CCD48A12E99E98AEDFB11A86，原 diff B676210F46137C3BCEBAC5A9737C3783DA892C4565CB5541A8B31C2392C7C07C 保留。五项 instrumentation 已编译，实际拒权/通知/FGS/重建/清理尚未设备验收。M1 debug R8/keep 和 M2 release Compose mapping 留 final C。

K40 2696813b/API33 是当前调试设备；K70/Android16 仍为实际目标载体，历史 timings 不继承。此前 USER_RESTRICTED 安装失败保留；用户开启 USB 安装后，controller 重试两个 APK 均 Success/ADB exit0，pm path edith.phone 确认已安装（.local/p2-t14-device-evidence/20261007-043445-Install.log）。安装前 controller fresh hashes 匹配 fix1 debug FC12224B8EAC84CFC92F1E7B2BEB21F41BDC0E8CB21AD3070A85C445B46598DA/test 09DC4176D068A8771360AD3A64E1009F0BCFECDADD9B265F5EF6352692082515。拒权 instrumentation 在执行测试前 NoClassDefFoundError kotlin/LazyKt 崩溃，0 项测试通过（20261007-043523-Denied.log）；ADB exit0 不是成功，实际权限/服务场景未验证。controller 修复该运行时故障后再执行设备验收，T15 等待此修复。用户允许离开期间由 ADB 点击安装确认；当前 launcher 有 EDITH、无安装弹窗。D31-D37、r3/plan/Inline 持续，不重复审批；无采集/network/D36 上传。真实格式/引擎/许可/RTF/质量/声纹校准/Keystore/ACK/TTL/佩戴可见/T19 门均开放。

批准正文 r3/A/B/C 实比 70e8d96：只归一化审核状态、checkbox 和换行后四份一致，无正文 drift。证据：docs/project/evidence/2026-10-07-p2-t14-review.md、.local/p2-t14-report.md、.local/p2-t14-fix1-rereview.md、.local/p2-resume-record-audit.md。原生 C 账本 .superpowers/sdd/2026-10-06-p2-c-android-device-loop/progress.md 由 controller 更新；本次只同步记录及主 checkout 既有导航。以下旧 checkpoint 段落为历史，以本入口为准。

## 历史 checkpoint18 及此前记录


checkpoint: p2-exec-20261007-18

当前接续checkpoint18：记录HEADaa5b5e2c9a51ef649310d3ccfede88345afc3168（checkpoint17docs），软件459d4a9；原21T14paths staged未提交，审查baseline diff B676210F46137C3BCEBAC5A9737C3783DA892C4565CB5541A8B31C2392C7C07C/index tree125d8a2eaf7561d468cf9294c774b422b798d8fe保留。Task review及stored-evidence audit已收取：spec issues/quality Needs fixes，无Critical，I1 close重入死锁/I2真实Android权限拒绝测试缺失；T14未accepted/未提交。实际native /root/p2_t14_fix1 gpt-6.1-sol/high clean fork已dispatch，round1/5 I1/I2 open，独占D-local Gradle；I1 compiled行为RED已复现1test/1failure（36s/18executed），GREEN/covering/package交付及scoped复审未收取，不提前称通过。旧CLI28047已退出，此为fresh native fix，非resume；T15-T19未实施、P2未完成。[B已接受软件证据](../../../docs/project/evidence/2026-10-07-p2-b-final-review.md)。

本次controller只读ADB确认phone2696813b/glasses1901092544027662 device；pm path edith.phone exit1/未安装，window policy showing=false/screenOFF，仅readiness。无P2install/capture/D36upload；D37K40/API33/r3/plan/Inline/D31/D32Ready/D33/D35/D36持续，无新approval。旧checkpoint17 reviewpending/auditpending或Gradle释放仅历史，当前以已收取审查、fix1独占为准。

## 当前调试设备（D37）

D37 / Confirmed / 2026-10-07：用户原话“调试设备已切换至红米K40并已连接至电脑。”controller于UTC2026-10-06T19:22:41Z只读清单确认phone2696813b / M2012K11AC / alioth / Android13/API33 / incrementalV816.0.10.0.TKHCNXM、AI App1.14.19.0920（versionCode10140019）；glasses1901092544027662 / incremental1.26.011-20260929-150201。当前P2调试/验收目标改为K40；旧K70/Android16/PBR8U48PWKNREUMR证据为历史，不继承其时延/引擎/native/设备结果。K40 API33通知/权限/FGS、真实PCM/native兼容性、RTF/质量/延迟均未验证，阈值不变。D31/D32Ready/D33/D35/D36原范围持续，不新增审批/上传权限；无P2安装/采集/D36上传。[K40切换实读证据](../../../docs/project/evidence/2026-10-07-p2-k40-device-switch.md)。下次实际设备操作前重新枚举；先收取T14/核查APK再安装，当前worker没有设备授权。

历史设备快照与持续授权：checkpoint12 controller实读phone/glassesonline、Android16/API36/眼镜1.26.011-20260929-150201；现由D37的K40清单替代当前目标，本record worker不操作设备。T01-T13软件无需手机，实际C安装/设备测试时才请求协助，纯状态无question popup。D31仅既有固定合成句STATUS_COMPLETED/exit0/process1101ms连接证据，无body/HTTPcategory/usage/firstToken/readable，不计质量/显示/取消/延迟或P2通过；无新请求/P2安装/采集/D36转写上传。D31/D32Ready/D33/D35/D36持续，精确30句不重问。初始reflection/absence RED流程偏差、后续行为RED与两个Minor保留最终审查；所有真实格式/引擎/模型质量/speaker/Keystore/ACK/TTL/佩戴可见/T19门开放，不进入P3。

Historical checkpoint12 entry: Task13 original CLI exec79140 exit0/report delivered; independent /root/p2_t13_task_review Needs fixes. I1 same-turn repeated Help permits old turn-wide stale-admission cleanup to erase newer hint; actual fix1 CLI exec33377 (gpt-6.1-sol/high) exclusively owns Gradle, appended report pending. Frozen14-file staged diff SHA25628B05D59FC0B728B75D1EB40C45CCA2D5B23FC84D9869B14C7ADF722F0AE1BDB, uncommitted; no native implementation followup is invented. Original fresh32suites/278tests/0failure-error-skip and manifest280/0mismatch retained; early298 arithmetic corrected. Initial reflection/absence RED weaker than requested compiled behavior RED; later typed behavioral tests do not retroactively remove this deviation. Minor fixed provider trace code and assembler cumulative buffer/scan bounds deferred, permanent/final review required.

Historical checkpoint12 preparation: Native B ledger .superpowers/sdd/2026-10-06-p2-b-pipeline-model/progress.md and native C .superpowers/sdd/2026-10-06-p2-c-android-device-loop/progress.md / task-14-brief.md exist and are controller-owned/read-only for this record sync. C detailed scan/local Android rulings are real preparation; T14/T16 preflights do not implement T14-T19. Next collect fix1/review/verify before T13 completion or C implementation. Only actual C install/device operations request physical assistance; status has no question popup. This hand-maintained ledger is not native tool output.

## Historical checkpoint11 snapshot

Current checkpoint11: actual software HEAD b5be862a889569a868e5fb615a030ec8b0aacf9e, T01-T12 software complete. T12 original Important inherited authenticator401 follow-up addressed by original worker: direct401/proxy407 RED2tests/2failures, GREEN2/client24; independent scoped specPASS/qualityPASS,0open. Controller model5suites/90fresh0failure-error-skip plus toolclasses exit0/6s/1executed11up-to-date. Original119 snapshot is historical. Durable evidence docs/project/evidence/2026-10-07-p2-t12-review.md. T13 actual isolated ephemeral Codex CLI gpt-6.1-sol/high exec79140/session01a11244-6b87-7243-9d23-768f3c6e2b02, exclusive D-local Gradle; report/final pending. T14-T19 unimplemented, T14 may have read-only preflight only. Native B ledger/controller-owned progress.md and task-13-brief.md retained, no invented native dispatch. All older entries below are historical; latest recorded ADB glasses only/phone absent, user notified, D31/D32Ready/D33/D35/D36 persist. No real config validation/install/capture/model/upload, full P2 notCompleted, no P3.

Task12 software complete /b5be862: original45/fix10 controller fingerprint checks0mismatch at original boundaries. Record-worker recheck fix10/10; original source fingerprints for two client files superseded by fix1, settings now differs through active T13 includes, config hashing omitted. Task12 B Steps1/2/3/5 checked, Step4 remains unchecked: future phone-app graph cannot yet be verified, explicit T14/T19 assembly gate. No claim each branch has strong behavioral RED.

T13 local rulings: turnBudget30s, hintTTL5s bounded by turn, opaque activation/request trace IDs and host-only identity-fenced cancelOutputTurn through arbiter; wrong choice costs local diagnostic/host policy integration rework, no API1 or upload scope change. Full text .local/p2-t13-controller-context.md / native B ledger. Controller owns native ledger/source/index/commit, T13 owns exclusive Gradle; this worker owns records only.

User D36 accepted fixed corpus p2-fixed-zh-30-v1's 30 ASR test transcripts to configured DeepSeek. Off-script content must not be sent; no audio/embedding/resume/personal data. Exact reply and JSON hash in project DECISIONS and .local/p2-corpus-upload-pending.md. Local preparation complete (30 unique ids); no capture/upload or real gates claimed.

Historical checkpoint08: T01-T10 software complete; T10 commit01a378c, baseec3226b. Original I1 independent scoped re-review Refuted, session42087 exit0; controller persisted returned report because read-only sandbox denied write. Production unchanged; two new regressions and12fresh forced repository tests passed. Root session16040 exit0,28s/7executed29up-to-date, not all XML fresh. T11 dispatched /root/p2_t11_implementation base01a378c, exclusive Gradle owner; T11-T19 incomplete. Durable evidence docs/project/evidence/2026-10-06-p2-t10-review.md. Native SDD workspace resolved by helper on recovery at .superpowers/sdd/2026-10-06-p2-b-pipeline-model/progress.md; this older manual ledger and all historical entries remain preserved. Approved r3/A/B/C bodies rechecked against70e8d96: match with only state/newline/progress-checkbox normalization; no body drift. T09 I4 remainsT16. Continue P2; no checkpoint stop/P3.

historical_checkpoint: p2-exec-20261007-11

Historical checkpoint10 Task11 complete /0fe05e9: original review71685 exit0, I1 independently REFUTED (approved origin unspecified/official zero samples), I2 fixed by original worker and scoped PASS. Fix RED5/2 failures, final8suites/77fresh zero failures/errors/skips; root exit0/21up-to-date. Persistent evidence docs/project/evidence/2026-10-07-p2-t11-review.md. Original review/RED/GREEN retained. Task12 next/inprogress, requirements .local/p2-t12-controller-context.md + native task-12-brief.md; real probe not yet run. Native B ledger carries full rulings/costs. Latest ADB onlyglasses; phone disconnected, D32 permission remains.

Recovery continuation after latest user steering: actual linked worktree/branch/HEADec3226b verified. Original T10 ephemeral CLI session99889 interrupted and collected exit1 after sandbox blocked exact Maven network access (getsockopt Permission denied). Original PID57396 and java processes absent at handoff. Existing meaningful tests/stubs retained, task not complete. D-local Gradle distribution .local/gradle-9.4.1 copied from existing cached C distribution; initial C zipfs AccessDenied retained as diagnostic evidence, no C cache deletion. /root/p2_t10_recovery now exclusively owns D-local Gradle and resumes .local/p2-t10-brief.md + recovery-context.md; no second implementation worker. Independent reviewer will follow its report.

User D32 device operations paused until user says phone Ready. D33 explicitly permits preannounced fixed non-sensitive Chinese sentences captured in phone RAM for local ASR/speaker quality testing, without raw recording persistence or text/audio upload. D31 exact fixed model probe remains authorized; no additional transcript-upload authority. D34 requests further online official PCM declaration search; /root/p2_pcm_online reads .local/p2-pcm-online-followup-brief.md, output .local/p2-pcm-online-followup-report.md. No devices or model requests performed during this continuation. /root/p2_acceptance_readiness audits remaining C gates read-only. This is Inline assistance, not Agent Teams or a new user-visible chat.

Latest steering: user phone Ready / continue all work; device pause revoked. Fresh adb inventory sees both original serials (never reused transport id); properties phoneAndroid16/API36, AIApp1.14.19.0920 and glasses system/bootimage incremental1.26.011-20260929-150201 read. No install/audio/model request at this boundary. T10 modality mapping ruling .local/p2-t10-modality-ruling.md: instructions alone not TEXT_REQUEST, explicit text input/history/context requires TEXT; text consent still required, media operations separately verified. Cost if wrong local gate rework; no encoder/upload expansion.

Recovery continuation: user explicitly requested execution through P2 closure. Actual HEAD22c0d5d / branch codex/p2-public-speech-text / D worktree confirmed,10 T09 files staged. All approved r3/A/B/C bodies match70e8d96 after newline/checkbox normalization. Independent reviewer /root/p2_t09_review wrote .local/p2-t09-review.md: spec Issues found/quality Needs fixes. I1 old callback source fence, I2 startup UNKNOWN poisoning endpoint, I3 post-save consent exception cleanup are Important and require fix round1 before commit. No task completion or new approval inferred.

Ruling: I2 may defer a LocalSpeakerService startup snapshot until its sample-duration quality lower bound can be satisfied, or use explicit internal provisional metadata; never ignore arbitrary UNKNOWN/conflicting sources or delay ASR. Why: ordinary1000ms policy otherwise always poisons a useful endpoint result. Cost if wrong: local speaker scheduling/attribution rework, no public DTO change or real quality claim.

Ruling: I4 metadata retention is parked for T16 before sustained-session acceptance. Short tests show no measured failure, approved T09 defines no retention count; keep finding visible and require finite host-owned bound/eviction evidence at integration. Cost if wrong: local retention/provider rework and failed sustained gate; not a waived P2 acceptance requirement.

Live source audits: .local/p2-pcm-source-brief.md and .local/p2-engine-source-brief.md are read-only T17/T16 preparation; no downstream source implementation before T09 gate. ADB inventory exit0 sees only RG_glasses1901092544027662; target phone absent. No install/capture. D31 fixed text remains authorized; separate expanded fixed-test-sentence capture/text-upload question pending, no dependent operation performed.

Later D32 user grant: target phone connected, run planned real acceptance and notify for unlock/assistance. Fresh inventory exit0 sees phonePBR8U48PWKNREUMR and glasses1901092544027662. No install/capture yet. Original implementer fix1 returned77tests green and released Gradle; controller found deferred startup never reattempted during live speech, contrary to r3 section4. Controller taking exclusive Gradle for focused live-duration RED/patch/GREEN before independent re-review, without overlapping implementer. Public signatures/calibration abstention unchanged.

Controller duration correction complete: compiled .local/p2-t09-live-snapshot-red.log1failure, final .local/p2-t09-live-snapshot-green.log exit0/16s/audio73fresh + unchanged storage/plugins4; no Java/Gradle remains. Exact combined fix .local/p2-t09-fix1-plus.diff reverse-apply/check passes, SHA256D95CB951B8718AA3C9C529F558470ACE230FBD8ECE52FBC9E70E170583364908. Independent scoped re-review launched via ephemeral read-only Codex CLI (multi_agent disabled, output/report D); session71458 must be collected before committing/next task. This is an ordinary review worker, not Agent Teams/new user-visible chat. Report .local/p2-t09-rereview.md; log .local/p2-t09-rereview-cli.log. No real capability claims.

Device follow-up: phone disappeared from fresh adb inventory after initial connection; targeted property/lock-state commands returned device-not-found. Glasses API32/buildSKQ1.240613.001 read successfully. No state inferred about phone lock/version; user notified to check connection/USB debugging. Still no device install/capture/model request. D32 device grant remains valid when ready.

This is a repository-maintained execution ledger. The executing-plans skill does not automatically create a native ledger file; this record is not a fabricated tool output.

Approved: P2-DESIGN-r3 and P2-PLAN-SET-r1. User reply: "批准计划，Inline Execution（推荐）". Exact approved plan hashes are in docs/project/evidence/2026-10-06-p2-plan-review.md.

Execution: Inline, no Agent Teams. Ordinary leaf agents assisted implementation/review under the controller. Verified isolated directory D:\Project_new\EDITH\.worktrees\p2, branch codex/p2-public-speech-text. Approved documentation baseline 70e8d963d947e3dfabf3232367ae799879b418c4; original source baseline 890ed79. Preflight passed: 8 suites / 13 tests / zero failures, errors or skips. See docs/project/evidence/2026-10-06-p2-execution.md and .local/p2-baseline.log. T09 is next.

- [x] P2-T01 Audio/time/provider contracts (41212d2; RED exit1, GREEN exit0; target 13 tests)
- [x] P2-T02 Model/profile/storage contracts (6d17b91; RED exit1, GREEN-r2 exit0; 13 target tests)
- [x] P2-T03 Scoped cancellation/injection (632079a + e6806a6; review regression RED/GREEN)
- [x] P2-T04 Lifecycle/input gate (17cf5c9; RED missing APIs, root GREEN 42 tests)
- [x] P2-T05 Backpressure/events (632b1f8; root GREEN plus T04 reentrancy/session fix)
- [x] P2-T06 Output arbitration/TTL (73a98ef; root57 tests, A software boundary)
- [x] P2-T07 PCM/framing/replay (7b55bb5; PCM14 tests; root77 tests with host fix9564304)
- [x] P2-T08 VAD/ASR pipeline (cd27574; 29 new tests/106 XML; independent re-review Approved)
- [x] P2-T09 Speaker/attribution (ec3226b; I1/I2/I3 fixed and scoped re-reviewPASS, I4 retainedT16; root exit0, XML138/0failure)
- [x] P2-T10 Profiles/gate/budget (01a378c; independent I1 Refuted;12fresh repository regression tests, root exit0)
- [x] P2-T11 SSE/Responses (0fe05e9; scoped specPASS/qualityACCEPTABLE,0open;77fresh tests)
- [x] P2-T12 Controlled HTTP/development loader (software b5be862; scoped fixPASS,0open; Step4 future phone-app runtime graph remains open for T14)
- [x] P2-T13 Trace/hints/diagnostic consumer (software165221e; B final fix459d4a9, I1/I2 ADDRESSED/0 new issues; initial reflection RED deviation preserved)
- [ ] P2-T14 Android host (FixInProgress round1/5; original review Needs fixes/I1-I2 open, original stored audit collected; actual/root/p2_t14_fix1 owns Gradle, I1 compiled RED1/1, GREEN/report/scoped review pending; original21paths stageduncommitted, no acceptance/device claim)
- [ ] P2-T15 Keystore/profile UI
- [ ] P2-T16 Engine provider/selection gate
- [ ] P2-T17 CXR/PCM format gate
- [ ] P2-T18 Glasses/clock/TTL
- [ ] P2-T19 Real loop/acceptance

T01-T08 complete at JVM software scope; latest software cd27574. XML19 suites/106 tests/zero failures, errors or skips; four-module85 tests freshly rerun, root final verification UP-TO-DATE. T08 scoped re-review Approved for spec/quality (.local/p2-t08-fix1-review.md), no open/deferred findings. Durable findings/results in docs/project/evidence/2026-10-06-p2-execution.md. NextT09. No API key read, model request, device capture or upload has been performed.

Ruling: PcmIngress exposes FIFO pollGap notifications by affected epoch/continuity. Backlog64 closes PCM admission until drained and explicitly reopened; notifications cannot be overwritten. Worker reset/cancel consumption belongs to T08/assembly. Default PCM bound2000ms/frame20ms; PCM16 mono LE/BE only. Cost if wrong: local integration rework, no real-device capability implied.

Ruling: critical host overflow closes atomic scope/event admission immediately, provider cancellation runs on owner. Already admitted work may finish; subsequent calls reject. Full validate/configure/activate callbacks guard lifecycle reentry. Device ACK callbacks enqueue on output owner. Existing apiVersion1 retained.

T08 initial implementation/review chronology (historical): compiled RED15, self-review RED2+RED2, initial GREEN23/fresh79/XML100. Final fix evidence supersedes those counts, preserving original logs/snapshot. No running Gradle session or device/model operations at completion.

T08 independent review .local/p2-t08-review.md: spec Issues found, quality Needs fixes. Important I1 deferred speaker reset can erase replacement work or emit replacement transcript after reset failure; I2 stale asynchronous failure is not revalidated inside worker fault transition. Controller verified both transitions against current source. Fix round1/5 dispatched to original implementer; review snapshot .local/p2-t08-review-base and full diff retained. Cannot-verify real engine/PCM/Android assembly/quality items remain C/T09 gates; no task completion claim.

Task 8: fix round1/5 (2 addressed,0 open; I1 reset completion, I2 atomic stale-fault admission; uncommitted snapshot diff .local/p2-t08-fix1-review-package.md then commitcd27574). RED4 plus second-gap RED1; GREEN29 pipeline/fresh85/XML106. Corrected three report-only SHA256 errors from raw files; cause unverified, reviewer independently confirmed final hashes.
Task 8: complete (commits26ce8e8..cd27574, review clean). Final root test exit0/UP-TO-DATE; evidence and final review retained. No parked/deferred findings.

Ruling: reset completion uses ordered FIFO8 of frames/gaps, frame<=40ms (maximum320ms deferred PCM); failure/close discards it and saturation faults/pauses. Cost if wrong: local scheduling/throughput rework; no new device guarantee.

Ruling: T08 speaker cadence is an initial snapshot plus endpoint snapshot, maximum8 outstanding windows, active PCM maximum20s including pre-roll. The worker streams ASR immediately; external notifications/cancel run outside admission locks. Cost if wrong: local provider/cadence rework; real voice quality remains T09/C16.

T09 preflight audit: .local/p2-b-continuation-audit.md; approved design/A/B/C bodies match70e8d96, only35 completed checkboxes. T09 brief .local/p2-t09-brief.md contains its mechanically extracted approved task.

Recovery 2026-10-06: actual HEAD afa1d26, only three controller records dirty; no java/Gradle process or running leaf. Corrected premature T09-start records before dispatch. Recompared all four approved bodies against70e8d96 (newline/checkbox normalization only): all match. Dispatched /root/p2_t09_implement with the existing brief; confirmed required skill reads and exclusive D-local Gradle ownership. T09 remains incomplete until independent review.

Consent D31: user explicitly allowed fixed synthetic DeepSeek text "只回复测试通过" via call_gxIzNtAUqkVr2fANeo58uyso reply. No real transcript/audio/personal data authorized. Wait for T12 controlled implementation and valid local configuration before sending; no request or key body read at this boundary.

Ruling: T09 keeps SpeakerProfileStore as a platform-neutral interface with separate memory implementation; LocalSpeakerService owns an explicit full id/revision SELF ref. Enrollment uses host-owned authorization and verified-window quality evaluation, default deny. Total sample span is only an upper bound on voiced duration; effective voiced duration must be host-supplied and <= span. Cost if wrong: provider/quality assembly rework, no new personal capture authority.

Ruling: T09 preserves A DTOs and T08 revision/final behavior. Transcript/speaker continuity is registered from accepted frame/window provenance inside audio-runtime; missing/conflicting provenance yields UNKNOWN and gaps clear it. Do not encode identity in eventId or infer continuity from timestamps. Cost if wrong: local timeline/assembly rework; no public API break authorized.

T10 preparation only: .local/p2-t10-brief.md mechanically includes approved Task10; dispatch waits for T09 review/commit. Ruling: ProfileRepository is an interface plus separate memory implementation for later Android provider; snapshots are defensive and profile revisions must not repeat after delete/recreate. Changes always revoke verification/pending work, but keep unchanged secret handles and revoke only replaced/deleted handles. Cost if wrong: local repository/provider integration rework; no new upload authority.

Ruling: T10 removal ids refer to original complete history pairs (history:N), crop oldest pairs before least-important context with deterministic ties; protect instructions/current input and use conservative UTF-8 bytes plus framing overhead with Long arithmetic. Malformed uncroppable history is rejected rather than half-deleted. Cost if wrong: local budget/history compatibility rework; no extra model request or precise-token claim.

Independent read-only protocol audit /root/p2_responses_audit uses .local/p2-responses-audit-brief.md, output .local/p2-responses-audit.md. No Gradle/code/model endpoint operations; not a second implementer or T09 review.

T09 implemented uncommitted/staged for scoped review; report .local/p2-t09-report.md and complete diff .local/p2-t09-review-package.md (base afa1d26). Controller counted final XML: 7 target suites/68 tests/0 failures/errors/skips, all timestamps after final source/test edits; inherited29 pipeline and both actual plugin tests included. Expected compiled RED11 plus self-review race/profile/quality RED retained; task still incomplete pending independent spec/quality verdict. Implementer released Gradle, no next task started.

T09 checkpoint05: no independent review dispatched/verdict yet; .local/p2-t09-review-prompt.md and .local/p2-t09-controller-risks.md are next review inputs. Static questions are unverified, not asserted defects: old admitted assessment after gap may obtain replacement source proof; metadata retention may grow for uninterrupted long sessions. Root test exit0/UP-TO-DATE; whole XML21 suites/129 tests zero failures/errors/skips, 68 target freshly rerun. No running test session/java process. Keep source staged/uncommitted until review/fix/commit; T09 steps remain unchecked. T10/T11 briefs ready only, no implementation. Durable fingerprints/limits in execution evidence.

Protocol audit complete: .local/p2-responses-audit.md; DeepSeek official guide HTTP200 has semantic event tables but no raw SSE identity/status/error schema. T11 must preserve created-response identity fallback and monotonic-with-gaps sequences, label fixtures synthetic, then retain real wire probe gate. Not a task completion or capability verification.

checkpoint: p2-exec-20261007-18

checkpoint: p2-exec-20261007-20

checkpoint: p2-exec-20261007-22

checkpoint: p2-exec-20261007-24

checkpoint: p2-exec-20261007-29
