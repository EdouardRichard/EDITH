# 脱离唤醒「我在」回声静默替换（2026-10-28）

## 范围（业主限定）

只修**脱离眼镜路线**的唤醒确认「我在」：不让它进入 ASR/模型，不让它破坏唤醒后用户语音的识别。**不改**回答 TTS、持续对话窗口、眼镜模式、主动辅助、`PlaybackGate` 既有语义。

## 问题与根因（2026-10-27 23:08 真机日志）

- KWS 命中前 ASR 已在持续转写（`P3_ASR revision=0` 早于 `P3_KWS hit`）；
- ack 播放期间 ASR 仍 final 一次（`chars=3`），即「我在」被转写；
- `PlaybackGate` 只抑制 KWS/声学标签/片段捕获，**不抑制 ASR**；
- 后果：ack 文本进模型；ack 与用户语音抢同一个 turn，用户后一句识别残缺。

## 方案：半双工回声静默替换（EchoMuteWindow）

- 新增 `com.sheen.rokid.phone.session.EchoMuteWindow`：窗口只由**播放事件**定义，与内容/音色无关；
  - `open()`：请求播放的时刻同步开窗（覆盖输出延迟），并挂 5 s watchdog；
  - `close()`：`onDone`/`onError` 回调 + 150 ms 混响尾关窗；
  - `covers(frameNanos)`：帧时间戳落在窗口内即为自播回声；
- `AudioIngressLoop` 帧分发点：窗口内帧 `silenced()`（样本换零、时间轴/epoch/range 保留，避免断流杀 turn），窗口外原样；
- `emitWake`：detached 且 ack 被接受 → 跳过 beep；**WAKE 事件不延迟**，回合与 ASR 立即打开（零新增延迟）；
- `AndroidSpeechPlayer.speakText(text, id, onState)`：ack 专用入口，复用同一可用性检查/监听器/`PlaybackGate`；
- 窗口只在 detached ack 打开：眼镜模式、回答 TTS、听写均不受影响（`muteWindow` 默认 null / 从不 open）。

## JVM 证据（2026-10-28 00:3x）

- `EchoMuteWindowTest` 5/0：开窗即静音、尾边距、开前帧不静音、watchdog、close-no-op/重开；
- `AudioIngressLoopTest.framesInsideTheEchoMuteWindowReachBothConsumersSilenced`：窗口内 ASR 与 KWS 均收全零帧、时间轴连续；尾边距内继续静音；越尾后真实样本恢复；
- `DetachedStartTest`：detached wake → `ack=["我在"]`、chime=0、`P3_WAKE` 立即 1 次；glasses wake → ack=0、chime=1；
- `AndroidSpeechPlayerTest.speakText...`：精确文本、gate 开/关、STARTED/FINISHED 回调；
- 定向 4 类 35 tests 0F/0E；app 模块全量 **148 suites / 1693 tests / 0F / 0E**（1 个 XML 含 skipped-assumption，Gradle 判定 SUCCESS）。

## 设备状态

- release APK `E:\edith-0.4-builds\EDITH-phone-0.4.0-preview-release.apk`，1,090,267,283 B，SHA-256 `6B6B262176EA34CD2BE4A21A90B68606211FE9135F3D3D9D9E4C3B5D653DDA12`；
- K40 `install -r` Success，设备 `base.apk` 同哈希；`P4_MODELS state=ready`、无 FATAL；a11y 4 项 bound；
- **真机 ack 交互待业主复测**：预期唤醒后立即听到「我在」，随后说的话只产生用户指令转写，模型不再收到「我在」。

## 已知代价（业主接受）

- ack 播放的 ~0.6–0.8 s 窗口内用户抢话会丢（半双工固有代价；AEC 阶段再救回）；
- 窗口外（含尾边距后）零新增延迟。
