<div align="center">
  <picture>
    <img alt="EDITH" src="docs/assets/brand/readme-logo.png" width="14%">
  </picture>
</div>

<h3 align="center">
EDITH: An Always-On Proactive AI Agent on Android, Connected to Rokid Glasses
</h3>

<p align="center">
  | <a href="#demo-videos"><b>Demo</b></a> |
  <a href="#technical-routes"><b>Technical routes</b></a> |
  <a href="#project-structure"><b>Project structure</b></a> |
  <a href="#notes"><b>Setup notes</b></a> |
  <a href="LICENSE"><b>Apache-2.0</b></a> |
</p>

<p align="center">
  <strong>English</strong> | <a href="README.zh-CN.md">简体中文</a>
</p>

> **Preview status:** EDITH is currently in preview (version `0.5.0-preview`). Its features are
> usable, but performance, stability and the overall experience still need optimization.

EDITH (伊迪丝) is an end-cloud collaborative AI agent: an Android host app on your phone plus a
custom app on Rokid Glasses. The glasses are its microphone, speaker and display; the phone is its
ears, hands, memory and brain. Microphone audio streams from the glasses over the Rokid AI App's
CXR-Link session; the phone segments and transcribes it on-device, calls the LLM you configure,
operates Android apps through the accessibility service and Shizuku's shell, and communicates with
the user through text on the glasses HUD and TTS audio. From 0.4 the phone can also run in
**phone-only mode**, using its own microphone and screen; 0.5 adds a draggable EDITH-icon floating
ball on the home screen and independent translucent message bubbles for replies and tool steps.

- **Phone-only mode** — Enable it in Settings and a draggable EDITH-icon ball appears on the home
  screen. Drag it to an edge and it snaps there, half hidden. Long-press to show the microphone and
  dictate; the transcript is sent to the agent. The ball and the bubbles have separate opacity
  settings. In phone-only mode the answer is **not** followed by the automatic 10 s listening
  window; long-press the ball to speak the next turn.

- **Wake-word route** — Say “EDITH” or “伊迪丝”. The glasses panel opens, EDITH listens, recognizes
  the intent, perceives the phone (screen, notifications, media, calendar, device state), plans and
  executes multi-step actions in other apps (open apps/pages, tap, type Chinese text, scroll, dial,
  run shell commands), then answers on the glasses HUD and speaks the answer. It keeps listening
  for a follow-up utterance without another wake word inside the 10-second listening window.
- **Proactive route** — After you tap “Start” once, EDITH keeps listening and watching ambient
  context. A small on-device gate model (Laya, GPU) decides whether the user needs help right now;
  if so, EDITH pushes a silent hint to the glasses **without any wake word** and opens a 10-second
  follow-up window. After the user replies, a full agent turn runs immediately.

## Demo videos

**Wake-word route** — say “EDITH” or “伊迪丝”; the panel opens and EDITH executes the request.

<div align="center">
  <a href="docs/assets/videos/wake-up.mp4">
    <img src="docs/assets/videos/wake-up-preview.gif" width="65%" alt="Wake-word demo — click to play the full video">
  </a>
</div>

**Proactive route** — EDITH notices the context, offers help without a wake word, and continues after the reply.

<div align="center">
  <a href="docs/assets/videos/proactive.mp4">
    <img src="docs/assets/videos/proactive-preview.gif" width="65%" alt="Proactive demo — click to play the full video">
  </a>
</div>

**Phone-only mode (0.4; renamed and extended in 0.5)** — no glasses link; wake-word wake and floating-ball dictation.

<div align="center">
  <a href="docs/assets/videos/detached-wake.mp4">
    <img src="docs/assets/videos/detached-wake-preview.gif" width="65%" alt="Phone-only wake demo — click to play the full video">
  </a>
</div>

> Note: the execution is **sped up** — real time is a little over **one minute**.

## Compatibility and dependencies

The versions below are the configuration EDITH is developed and tested against. Newer firmware or
app versions may work, but they are not part of the validated baseline.

| Component | Version |
| --- | --- |
| Android phone | Android 9+ (`minSdk 28`); tested on Redmi K40, Android 13 / API 33 |
| Rokid AI App | `1.14.19.0920` (mainland build, `com.rokid.sprite.aiapp`, versionCode `10140019`) |
| Rokid Glasses | firmware `1.26.011-20260929-150201` (RG-glasses, Android 12 / API 32, 480×640) |
| Glasses link | CXR-Link SDK `client-l 1.1.2` + service bridge `1.0-20260715.121510-107` |
| Shizuku | `13.5.4+` (built against API `13.1.5`; device-tested with `13.6.0`) |
| Build toolchain | JDK 17, Android SDK Platform 35, Gradle wrapper 9.4.1 |

All four on-device model sets are bundled inside the phone APK (about **1.1 GB**); the first
launch materializes about **1.0 GiB** (1.03 GiB). See [Model files](#2-model-files-bundled) below.

## Technical routes

0.4 keeps both routes and adds a phone-only mode variant of the wake route, a text/dictation
input row on the phone, and model provisioning from APK assets. 0.5 adds the home-screen floating
ball and independent message bubbles to phone-only mode. The default glasses configuration
described first is unchanged.

### Wake-word route

Glasses microphone → CXR audio stream → PCM ingress. The same PCM stream feeds two consumers:
wake-word spotting (KWS) and VAD + streaming ASR. A KWS hit on “EDITH / 伊迪丝” raises a wake
event, opens the glasses panel and opens a WAKE turn; VAD/ASR runs continuously from session start
(it cannot wait for the wake: the wake word is usually at the start of the utterance, and the
proactive route has no wake word at all). A final transcript is admitted into the Agent only while
the WAKE turn is waiting for it — with no turn waiting, a final does not open a wake turn; it goes
to the proactive trigger path instead. `AgentLoop` with the configured cloud model → tool execution
and perception → answer on the glasses panel and phone-side TTS. The same CXR session also carries
the display commands and the follow-up audio. With phone-only mode on, this chain starts at the phone
microphone instead of the CXR uplink; KWS, VAD, ASR and everything after transcript admission are
the same (see below). It also wakes with the screen off or locked (foreground
service + `PARTIAL_WAKE_LOCK`) and answers every accepted wake with a fixed spoken acknowledgement
「我在」 inside an echo-mute window: microphone frames are silenced while the phone speaks, so the
acknowledgement never reaches the ASR or corrupts the user's next command, and the WAKE turn opens
without waiting for the acknowledgement to finish (zero added latency).

```mermaid
flowchart LR
    subgraph G["Rokid Glasses"]
        MIC["Microphone"]
        HUD["EDITH panel<br/>(accessibility overlay)"]
        SPK["Speaker"]
    end

    subgraph P["Android phone — EDITH host"]
        CXR["CXR-Link session<br/>(Rokid AI App)"]
        ING["PCM ingress<br/>16 kHz mono"]
        KWS["KWS spotter<br/>(sherpa-onnx)"]
        VAD["VAD<br/>(Silero)"]
        ASR["Streaming ASR<br/>(Zipformer zh/en)"]
        ADMIT["Transcript admission<br/>(WAKE turn awaiting)"]
        CYCLE["Conversation cycle<br/>WAKE route"]
        LOOP["AgentLoop"]
        PERC["Perception snapshot<br/>screen · notifications · media · calendar"]
        TOOLS["Tools<br/>AccessibilityService · Shizuku shell"]
        TTS["TTS<br/>(Android)"]
    end

    LLM["Cloud LLM<br/>(Responses / Chat Completions)"]

    MIC --> CXR --> ING
    ING --> KWS
    ING --> VAD --> ASR
    ASR -- "transcript" --> ADMIT
    ADMIT -- "admit" --> CYCLE
    KWS -- "wake hit: EDITH / 伊迪丝" --> CYCLE
    KWS -. "open panel" .-> HUD
    CYCLE --> LOOP
    LOOP <--> LLM
    PERC --> LOOP
    LOOP --> TOOLS
    TOOLS --> PERC
    LOOP -- "answer" --> HUD
    LOOP --> TTS --> SPK
```

**Components:** Rokid Glasses microphone/HUD/speaker · Rokid AI App CXR-Link transport
(`transport-rokid`: session, audio callback, custom commands, authorization) · PCM ingress ·
sherpa-onnx keyword spotter (8 phase-diversified streams) · Silero VAD · sherpa-onnx streaming
Zipformer ASR (bilingual zh/en, fp32 decoder) · `plugin-smart-helper` (WAKE route, prompts, memory,
skills, tool policy) · `agent-runtime` (`AgentLoop`, request assembly) · `model-runtime`
(Responses / Chat Completions over SSE) · `host-runtime` / `output-runtime` (output arbitration,
hint codec) · phone accessibility service and Shizuku shell (tool execution) · Android TTS over
Bluetooth.

### Proactive route

The session stays resident after one “Start”. Ambient perception and the CXR audio stream keep
running; context changes are events. A lightweight on-device gate decides whether the user needs
help right now, without calling the cloud model; a positive decision becomes a single hint turn
whose only tool is `propose_hint`. A reply to the hint runs a full agent turn.

```mermaid
flowchart LR
    subgraph G["Rokid Glasses"]
        HUD["EDITH panel"]
        MIC["Microphone"]
    end

    subgraph P["Android phone — EDITH host"]
        SENSE["Ambient perception<br/>foreground app · screen ·<br/>notifications · media · calendar"]
        EVENTS["Context events<br/>ASR final · context change"]
        GATE["Laya gate<br/>(on-device GPU)<br/>does the user need help now?"]
        HINT["HINT_TURN<br/>AgentLoop, maxSteps=1<br/>only propose_hint"]
        LISTEN["Follow-up window<br/>(no wake word, 10 s)"]
        FOLLOW["FOLLOWUP_TURN<br/>full AgentLoop + tools"]
    end

    MIC --> EVENTS
    SENSE --> EVENTS
    EVENTS --> GATE
    GATE -- "no: stay silent" --> SENSE
    GATE -- "yes" --> HINT
    HINT -- "silent hint" --> HUD
    HINT -- "opens window" --> LISTEN
    LISTEN -- "user replies" --> FOLLOW
    FOLLOW -- "answer / execute / confirm" --> HUD
    WAKE["Wake word"] -. "preempts" .-> FOLLOW
```

**Components:** resident session (`SessionService`, CXR audio, KWS/VAD/ASR) · perception snapshot
(`AndroidPerceptionProvider`) · Laya gate (`SystemOneService`, on-device GPU) · HINT_TURN
(`AgentLoop` with a terminal `propose_hint` tool) · `ConversationCycle` follow-up window · the same
tool face as the wake route for FOLLOWUP_TURN · glasses overlay panel via CXR custom commands.
When the gate declines, **zero** model requests are made. The two routes use independent
`ConversationStore`s.

### Phone-only mode (0.4; renamed and corrected in 0.5.1)

The **phone-only mode** setting makes the wake route independent of the glasses. Since 0.5 it also shows the EDITH-icon floating ball and the separate translucent message bubbles described above: drag and edge snap, long-press dictation, a thinking light ring, adjustable opacity, at most two bubbles at a time (whole bubble, text included, follows the slider), and old bubbles that fade away after 2 s. In phone-only mode an answer no longer opens the automatic 10 s continuation; the next turn is dictated by long-pressing the ball.

Since 0.5.1 the user-visible Chinese name is 「纯手机模式」 (0.5 preview builds said 「纯手机模型」). The internal `DetachGate` / `glassesDetached` identifiers and the `phone_only_overlay` notification channel id are unchanged. The 0.5.1 correction is a checkpoint: see the [0.5.1 evidence](docs/project/evidence/2026-10-29-0.5.1-correction.md) for the JVM/device readings and the remaining `NotMeasured` items.

```mermaid
flowchart LR
    MIC["Phone microphone"] --> ING["PCM ingress<br/>16 kHz mono"]
    ING --> KWS["KWS spotter"]
    ING --> VAD["VAD"] --> ASR["Streaming ASR"]
    KWS -- "wake hit: EDITH / 伊迪丝" --> CYCLE["WAKE route<br/>same turns, tools, prompts"]
    ASR -- "final transcript" --> ADMIT["Transcript admission"] --> CYCLE
    CYCLE -- "answer" --> LOG["Phone conversation log"]
    LOCK["PARTIAL_WAKE_LOCK<br/>edith:detached-wake"] -. "keeps KWS running<br/>with the screen off" .-> ING
    STORED["Stored proactive switch"] -. "effective off in phone-only mode" .-> CYCLE
```

With the setting off, the glasses routes above are unchanged: CXR connection, glasses wake, panel
display and follow-up behavior keep their existing semantics. Phone-only mode is a local
input/display choice, not a second CXR protocol.

## Project structure

- `phone-app/` — Android phone host: session and plugin host, perception, speech, model runtime, native action channels (accessibility / Shizuku)
- `glasses-app/` — glasses CustomApp: CXR-S bridge, overlay panel and rendering
- `common/` — contracts and presentation / output modules shared by both ends
- `tools/` — probes, evaluation harness and scripts (not shipped)
- `third_party/` — vendored build inputs such as the CXR SDK
- `docs/` — project documents, evidence, brand and demo assets

## Notes

### 1. Accessibility service (required)

EDITH cannot read the screen or act in other apps without Android's accessibility service. Two
services are involved:

- **Phone** — `com.sheen.rokid.phone/.actions.a11y.EdithAccessibilityService`: screen reading,
  semantic click / long-press / scroll, Chinese text input and screenshots.
- **Glasses** — `com.sheen.rokid.glasses/.accessibility.GlassesAccessibilityService`: it only lends
  its window token so the panel can be drawn as `TYPE_ACCESSIBILITY_OVERLAY` above the vendor home
  HUD (a system window that covers ordinary app overlays; the accessibility overlay's base layer
  311000 sits above the HUD's 271000). It reads no window content and draws nothing itself.

Enable each one over ADB. **Always read the current list first and append** — another app's service
may already be enabled and must not be overwritten. A written setting is not a bound service:
verify the binding in `dumpsys accessibility`.

```powershell
$phone   = "<phone-serial>"
$service = "com.sheen.rokid.phone/com.sheen.rokid.phone.actions.a11y.EdithAccessibilityService"

$old = (adb -s $phone shell settings get secure enabled_accessibility_services).Trim()
$new = if ([string]::IsNullOrWhiteSpace($old) -or $old -eq "null") { $service }
elseif (($old -split ":") -contains $service) { $old }   # already enabled: do not duplicate
else { "$old`:$service" }
adb -s $phone shell settings put secure enabled_accessibility_services $new
adb -s $phone shell settings put secure accessibility_enabled 1

# Must show the EDITH service under "Bound services"; a stored value alone is not proof.
adb -s $phone shell dumpsys accessibility | Select-String "Bound services|EDITH"
```

Use the same pattern on the glasses with component
`com.sheen.rokid.glasses/com.sheen.rokid.glasses.accessibility.GlassesAccessibilityService` and
serial `<glasses-serial>`.

### 2. Model files (bundled)

The four on-device model sets — ASR engine bundle, wake-word (KWS), proactive gate (Laya) and memory
embedding (EmbeddingGemma) — are **bundled inside the phone APK**. Users do not need a separate
Quark Drive download or an `adb push` to install or start using EDITH.

The complete APK is about **1.1 GB** (measured debug build: 1.11 GB / 1.0347 GiB). Plan for roughly
**2.2 GB** of free storage at install time (estimate: the APK plus the materialized copy). The four
sets together take about **1.0 GiB** (1.03 GiB) after materialization. Measured on the K40 acceptance
device with the debug build (2026-10-26), the first materialization took about **12.8 s** and a later
cold start reused the materialized copy in about **0.45 s**; the release build was not measured.
Until preparation finishes, the app shows its
preparing-local-models state and refuses to start a session rather than running with a partial bundle.

| Model set | Contents | Runtime location after materialization |
| --- | --- | --- |
| ASR engine bundle | encoder / decoder / joiner (ONNX), tokens, Silero VAD, speaker model, plus `engine-manifest.json` | Phone app-private `no_backup/engine-models/` |
| Wake-word (KWS) model | sherpa-onnx KWS Zipformer zh/en 3M: 5 runtime files + `keywords.txt` | APK assets `assets/kws/` (read in place, no separate copy) |
| Proactive gate (Laya) | Laya-Multilingual-LiteRT: 6 runtime files | Phone app-internal `files/` (6 files + bundle receipt) |
| Memory embedding | EmbeddingGemma 300M `.tflite` + SentencePiece vocabulary | Phone external files `Android/data/com.sheen.rokid.phone/files/models/embeddinggemma/` (internal fallback) |

The build verifies every file's bytes and SHA-256 against `tools/scripts/p2/model-bundles.json`; a
missing or mismatched file fails the build instead of shipping an incomplete bundle. It also derives
the bundle id from the pinned entries and refuses a manifest whose id does not match, so a changed
file digest always changes the receipt. The license and declaration texts (15 texts) ship under
`assets/models/licenses/`. On first launch and each APK update that changes the bundle, the app
verifies and materializes the assets again; an unchanged bundle is reused.

**Development builds.** The model binaries are not committed. To build a complete APK, stage them
and point `-PbundledModelsDir` at the staging directory:

```powershell
.\gradlew.bat :phone-app:app:assembleDebug -PbundledModelsDir=D:\Project_new\EDITH_model\k40
```

The default is `<repo>/../EDITH_model/k40` (the K40 bundle), with `engine-models-import/`, `files/`
and `embeddinggemma/` subdirectories plus `kws/` for the KWS models. The tracked `keywords.txt`
stays in `phone-app/perception/audio-android/kws/`. A complete build requires every file; a missing
or mismatched file fails the build.

For development/CI only, `-PwithoutBundledModels=true` omits `assets/models/**` and sets
`BuildConfig.BUNDLED_MODELS=false`. **Release variants reject this switch**; it must never produce
a shipped APK.

Model files are subject to their upstream licenses (Apache-2.0 for ASR/KWS/Laya, MIT for Silero,
Gemma Terms of Use for EmbeddingGemma) and are not part of this repository. The pinned
sources/revisions and retained license/declaration texts are listed in
`tools/scripts/p2/NOTICE.md` and `tools/scripts/p2/licenses/`; `distributionReview` remains
Incomplete.

### 3. Glasses-side ADB permissions

The glasses app is a sideloaded CustomApp. Install it with ADB, then grant the two special
permissions it needs:

```powershell
$glasses = "<glasses-serial>"

# 1) Install / update the glasses APK (e.g. .\glasses-app\app\build\outputs\apk\debug\app-debug.apk).
adb -s $glasses install -r <glasses-apk>

# 2) Overlay permission: the resident panel is a TYPE_APPLICATION_OVERLAY window drawn over other apps.
adb -s $glasses shell appops set com.sheen.rokid.glasses SYSTEM_ALERT_WINDOW allow
adb -s $glasses shell appops get com.sheen.rokid.glasses SYSTEM_ALERT_WINDOW     # expect: allow

# 3) Accessibility service: provides the window token for TYPE_ACCESSIBILITY_OVERLAY above the vendor HUD.
$component = "com.sheen.rokid.glasses/com.sheen.rokid.glasses.accessibility.GlassesAccessibilityService"
$old = (adb -s $glasses shell settings get secure enabled_accessibility_services).Trim()
$new = if ([string]::IsNullOrWhiteSpace($old) -or $old -eq "null") { $component }
elseif (($old -split ":") -contains $component) { $old }   # already enabled: do not duplicate
else { "$old`:$component" }
adb -s $glasses shell settings put secure enabled_accessibility_services $new
adb -s $glasses shell settings put secure accessibility_enabled 1
adb -s $glasses shell dumpsys accessibility | Select-String "Bound services|EDITH"
```

The panel's wake-lock and foreground-service permissions are normal install-time permissions and need
no ADB step. Re-check the overlay app-op and the accessibility entry after every reinstall; both can
be reset by the platform.

### 4. Glasses app depends on the Rokid development line

The glasses is a consumer device: there is no app store for third-party CustomApps. Installing the
EDITH glasses app requires the **Rokid development line** — USB debugging / developer access on the
glasses — and `adb install` as shown above. The special permissions in note 3 can only be granted
over ADB as well. On the phone side, the Rokid AI App must be installed, signed in and paired with
the glasses; the first EDITH session asks the AI App to authorize the CXR session (microphone
access), and that authorization must be accepted.

### 5. Phone-side setup

- **Shizuku**: install Shizuku 13.5.4+, start its server (root or ADB), then authorize EDITH in the
  Shizuku app. This provides the shell-UID channel behind `shell` / `open` / privileged commands;
  without it those tools degrade while accessibility tools keep working.
- **Notification access**: grant notification listener access so EDITH can perceive notifications.
- **Overlay**: grant “display over other apps” (`SYSTEM_ALERT_WINDOW`) for confirmation cards that
  must be visible above other apps. Missing permissions degrade that channel instead of silently
  widening it.
- **Runtime permissions**: notifications, calls (for `dial`), calendar (for day events), and
  “all files access” if you use the skill library under `/sdcard/EDITH/skills`.
- **Optional lock-screen unlock**: set a 4–16 digit PIN in **Settings → Unlock automatically**.
  It is stored only on this phone with an Android Keystore AES/GCM key and is used only to let
  device tools pass a locked screen while a session is running. Pattern locks and non-ASCII
  passwords are intentionally not supported.

## License

EDITH is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) for the full text.
Third-party components (vendor SDKs, model files, and other artifacts) remain under their own
licenses.

